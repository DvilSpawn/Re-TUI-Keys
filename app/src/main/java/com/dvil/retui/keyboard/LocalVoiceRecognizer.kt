package com.dvil.retui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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

    /** Silero VAD from the APK assets; a phrase ends after half a second of silence. */
    fun newVad(context: Context): Vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.2f,
                windowSize = 512,
                maxSpeechDuration = 30f
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1
        )
    )

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

/**
 * Records from the microphone and transcribes as the user talks. A voice activity detector splits
 * speech into phrases: the current phrase is re-decoded every [PARTIAL_INTERVAL_SECONDS] for a live
 * draft, and finalized when the user pauses. Dictation stops by itself after a long silence.
 * All [Listener] calls arrive on the main thread.
 */
internal class LiveDictation(
    private val context: Context,
    private val pack: VoicePack,
    private val listener: Listener
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onFinished(heardSpeech: Boolean)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<FloatArray>()
    @Volatile private var recording = false
    @Volatile private var cancelled = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = LocalVoiceRecognizer.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        // MIC rather than VOICE_RECOGNITION: some devices (vivo) capture the latter far too quietly.
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
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
        recording = true
        Thread({ readLoop(audio) }, "retui-voice-record").start()
        Thread({ processLoop() }, "retui-voice-decode").start()
        return true
    }

    /** Stops listening; the phrase in progress is still finalized. */
    fun stop() {
        recording = false
    }

    /** Stops immediately and drops anything not yet delivered. */
    fun cancel() {
        cancelled = true
        recording = false
    }

    private fun readLoop(audio: AudioRecord) {
        val buffer = ShortArray(LocalVoiceRecognizer.SAMPLE_RATE / 10)
        try {
            while (recording) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read > 0) queue.put(FloatArray(read) { buffer[it] / 32768f })
            }
        } finally {
            runCatching { audio.stop() }
            audio.release()
            queue.put(FloatArray(0))
        }
    }

    private fun processLoop() {
        var vad: Vad? = null
        try {
            LocalVoiceRecognizer.preload(pack)
            vad = LocalVoiceRecognizer.newVad(context)
            val heardSpeech = transcribeUntilDone(vad)
            post { listener.onFinished(heardSpeech) }
        } catch (error: Throwable) {
            recording = false
            post { listener.onError("Voice model failed to load") }
        } finally {
            vad?.release()
        }
    }

    private fun transcribeUntilDone(vad: Vad): Boolean {
        val rate = LocalVoiceRecognizer.SAMPLE_RATE
        val maxPhrase = LocalVoiceRecognizer.maxSeconds(pack) * rate
        val window = FloatArray(VAD_WINDOW)
        var windowFill = 0
        val preRoll = ArrayDeque<FloatArray>()
        var preRollSize = 0
        val phrase = FloatBuffer()
        var inPhrase = false
        var decodedSize = 0
        var processed = 0L
        var lastSpeechAt = 0L
        var heardSpeech = false

        fun finalizePhrase() {
            val text = LocalVoiceRecognizer.transcribe(pack, phrase.toArray())
            post { listener.onFinal(text) }
            phrase.clear()
            inPhrase = false
            decodedSize = 0
        }

        while (!cancelled) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (chunk.isEmpty()) break
            var offset = 0
            while (offset < chunk.size) {
                val take = min(VAD_WINDOW - windowFill, chunk.size - offset)
                System.arraycopy(chunk, offset, window, windowFill, take)
                windowFill += take
                offset += take
                if (windowFill < VAD_WINDOW) break
                windowFill = 0
                val samples = window.copyOf()
                vad.acceptWaveform(samples)
                while (!vad.empty()) vad.pop()
                processed += VAD_WINDOW
                val speaking = vad.isSpeechDetected()
                if (speaking) {
                    lastSpeechAt = processed
                    heardSpeech = true
                    if (!inPhrase) {
                        inPhrase = true
                        preRoll.forEach(phrase::add)
                        preRoll.clear()
                        preRollSize = 0
                    }
                }
                if (inPhrase) {
                    phrase.add(samples)
                    if (!speaking || phrase.size >= maxPhrase) finalizePhrase()
                } else {
                    preRoll.addLast(samples)
                    preRollSize += samples.size
                    while (preRollSize > PRE_ROLL_SECONDS * rate) preRollSize -= preRoll.removeFirst().size
                }
            }
            if (inPhrase && phrase.size - decodedSize >= PARTIAL_INTERVAL_SECONDS * rate) {
                decodedSize = phrase.size
                val partial = LocalVoiceRecognizer.transcribe(pack, phrase.toArray())
                if (partial.isNotEmpty()) post { listener.onPartial(partial) }
            }
            val silentFor = processed - lastSpeechAt
            if (recording && !inPhrase && silentFor >= (if (heardSpeech) END_SILENCE_SECONDS else START_TIMEOUT_SECONDS) * rate) {
                recording = false
            }
        }
        if (!cancelled && inPhrase) finalizePhrase()
        return heardSpeech
    }

    private fun post(action: () -> Unit) {
        main.post { if (!cancelled) action() }
    }

    private class FloatBuffer {
        private var data = FloatArray(LocalVoiceRecognizer.SAMPLE_RATE * 4)
        var size = 0
            private set

        fun add(samples: FloatArray) {
            if (size + samples.size > data.size) data = data.copyOf(max(data.size * 2, size + samples.size))
            System.arraycopy(samples, 0, data, size, samples.size)
            size += samples.size
        }

        fun toArray(): FloatArray = data.copyOf(size)

        fun clear() {
            size = 0
        }
    }

    private companion object {
        const val VAD_WINDOW = 512
        const val PRE_ROLL_SECONDS = 0.6
        const val PARTIAL_INTERVAL_SECONDS = 0.6
        const val START_TIMEOUT_SECONDS = 5.0
        const val END_SILENCE_SECONDS = 2.5
    }
}
