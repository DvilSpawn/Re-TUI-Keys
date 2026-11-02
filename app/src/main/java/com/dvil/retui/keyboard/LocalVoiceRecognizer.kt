package com.dvil.retui.keyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlin.math.max
import kotlin.math.min

/** Runs an installed [VoicePack] with sherpa-onnx. Loading is slow, so the last model stays loaded. */
internal object LocalVoiceRecognizer {
    const val SAMPLE_RATE = 16_000

    private var loadedKey: String? = null
    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun preload(pack: VoicePack) {
        recognizerFor(pack)
    }

    @Synchronized
    fun transcribe(pack: VoicePack, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val recognizer = recognizerFor(pack)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }

    /** Whisper only sees 30 seconds at a time; the other supported models take longer clips. */
    fun maxSeconds(pack: VoicePack): Int = if (pack.type == VoicePackManager.TYPE_WHISPER) 30 else 60

    private fun recognizerFor(pack: VoicePack): OfflineRecognizer {
        val key = "${pack.directory.absolutePath}@${pack.version}"
        recognizer?.takeIf { loadedKey == key }?.let { return it }
        release()
        return OfflineRecognizer(config = config(pack)).also {
            recognizer = it
            loadedKey = key
        }
    }

    private fun config(pack: VoicePack): OfflineRecognizerConfig {
        val threads = min(4, max(1, Runtime.getRuntime().availableProcessors() / 2))
        val model = when (pack.type) {
            VoicePackManager.TYPE_MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = pack.file("preprocessor"),
                    encoder = pack.file("encoder"),
                    uncachedDecoder = pack.file("uncachedDecoder"),
                    cachedDecoder = pack.file("cachedDecoder"),
                    mergedDecoder = pack.file("mergedDecoder")
                ),
                tokens = pack.file("tokens"),
                numThreads = threads
            )
            VoicePackManager.TYPE_WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = pack.file("encoder"),
                    decoder = pack.file("decoder"),
                    language = pack.option("language"),
                    task = pack.option("task").ifEmpty { "transcribe" }
                ),
                tokens = pack.file("tokens"),
                numThreads = threads
            )
            VoicePackManager.TYPE_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = pack.file("encoder"),
                    decoder = pack.file("decoder"),
                    joiner = pack.file("joiner")
                ),
                tokens = pack.file("tokens"),
                modelType = pack.option("modelType"),
                numThreads = threads
            )
            VoicePackManager.TYPE_SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = pack.file("model"),
                    language = pack.option("language").ifEmpty { "auto" },
                    useInverseTextNormalization = pack.option("useInverseTextNormalization") != "false"
                ),
                tokens = pack.file("tokens"),
                numThreads = threads
            )
            else -> throw IllegalArgumentException("Unsupported model type: ${pack.type}")
        }
        return OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80), modelConfig = model)
    }
}

/** Captures 16 kHz mono audio on its own thread until stopped or [maxSeconds] is reached. */
internal class VoiceRecorder(private val maxSeconds: Int, private val onLimitReached: () -> Unit) {
    private val lock = Object()
    private var samples = FloatArray(LocalVoiceRecognizer.SAMPLE_RATE * 4)
    private var count = 0
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = LocalVoiceRecognizer.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, rate / 5 * 2))
        } catch (_: RuntimeException) {
            return false
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return false
        }
        try {
            audio.startRecording()
        } catch (_: RuntimeException) {
            audio.release()
            return false
        }
        record = audio
        running = true
        thread = Thread({ readLoop(audio) }, "retui-voice-record").apply { start() }
        return true
    }

    /** Stops capture and returns everything recorded so far. */
    fun stop(): FloatArray {
        running = false
        thread?.join(1_000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        synchronized(lock) { return samples.copyOf(count) }
    }

    private fun readLoop(audio: AudioRecord) {
        val limit = LocalVoiceRecognizer.SAMPLE_RATE * maxSeconds
        val buffer = ShortArray(LocalVoiceRecognizer.SAMPLE_RATE / 10)
        while (running) {
            val read = audio.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            var reachedLimit = false
            synchronized(lock) {
                val take = min(read, limit - count)
                if (count + take > samples.size) samples = samples.copyOf(min(limit, max(samples.size * 2, count + take)))
                for (i in 0 until take) samples[count + i] = buffer[i] / 32768f
                count += take
                reachedLimit = count >= limit
            }
            if (reachedLimit) {
                running = false
                onLimitReached()
            }
        }
    }
}
