package com.dvil.retui.keyboard

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * An installed speech-to-text model. Packs are zip files holding a `manifest.json` plus the model
 * files it names; see VOICE_PACKS.md for the format.
 */
internal data class VoicePack(
    val id: String,
    val name: String,
    val version: Int,
    val languages: List<String>,
    val type: String,
    val files: Map<String, File>,
    val options: Map<String, String>,
    val directory: File
) {
    fun file(role: String): String = files[role]?.absolutePath.orEmpty()
    fun option(key: String): String = options[key].orEmpty()
}

internal data class VoicePackInstallResult(val pack: VoicePack? = null, val error: String? = null)

internal object VoicePackManager {
    const val KIND = "voice"
    const val TYPE_MOONSHINE = "moonshine"
    const val TYPE_WHISPER = "whisper"
    const val TYPE_TRANSDUCER = "transducer"
    const val TYPE_SENSE_VOICE = "sense_voice"

    private const val SCHEMA = 1
    private const val MANIFEST = "manifest.json"
    private const val DIRECTORY = "voice-packs"
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 64
    private const val MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024
    private val PACK_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val EXTRA_FILES = setOf("LICENSE", "NOTICE", "README.md", "README.txt")

    /** Roles each model type needs. Moonshine accepts either the merged or the split decoder layout. */
    private val REQUIRED_ROLES = mapOf(
        TYPE_MOONSHINE to listOf(setOf("encoder", "mergedDecoder", "tokens"),
            setOf("preprocessor", "encoder", "uncachedDecoder", "cachedDecoder", "tokens")),
        TYPE_WHISPER to listOf(setOf("encoder", "decoder", "tokens")),
        TYPE_TRANSDUCER to listOf(setOf("encoder", "decoder", "joiner", "tokens")),
        TYPE_SENSE_VOICE to listOf(setOf("model", "tokens"))
    )

    fun installed(context: Context): List<VoicePack> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && PACK_ID.matches(it.name) }
            .mapNotNull { dir -> runCatching { load(dir) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun find(context: Context, id: String): VoicePack? {
        if (!PACK_ID.matches(id)) return null
        val dir = File(root(context), id)
        return if (dir.isDirectory) runCatching { load(dir) }.getOrNull() else null
    }

    fun delete(context: Context, id: String): Boolean {
        if (!PACK_ID.matches(id)) return false
        val dir = File(root(context), id)
        return dir.isDirectory && dir.deleteRecursively()
    }

    /** True when the zip's manifest declares a voice pack; reads only up to the manifest. */
    fun isVoicePack(input: InputStream): Boolean = try {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return false
                if (!entry.isDirectory && flatName(entry.name) == MANIFEST) {
                    val manifest = JSONObject(String(readBounded(zip, MAX_MANIFEST_BYTES), Charsets.UTF_8))
                    return manifest.optString("kind") == KIND
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    } catch (_: Exception) {
        false
    }

    fun install(context: Context, input: InputStream): VoicePackInstallResult {
        val root = root(context)
        val staging = File(root, ".staging-${System.nanoTime()}")
        return try {
            check(staging.mkdirs()) { "Cannot create voice pack storage" }
            extract(input, staging)
            val pack = load(staging)
            val target = File(root, pack.id)
            val keep = pack.files.values.map { it.name }.toSet() + MANIFEST + EXTRA_FILES
            staging.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
            if (target.exists()) check(target.deleteRecursively()) { "Cannot replace ${pack.id}" }
            check(staging.renameTo(target)) { "Cannot install ${pack.id}" }
            VoicePackInstallResult(pack = load(target))
        } catch (error: Exception) {
            VoicePackInstallResult(error = error.message ?: "Invalid voice pack")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    internal fun parseManifest(json: String, directory: File): VoicePack {
        val manifest = JSONObject(json)
        require(manifest.optInt("schema") == SCHEMA) { "Unsupported voice pack schema" }
        require(manifest.optString("kind") == KIND) { "Not a voice pack" }
        val id = manifest.optString("id")
        require(PACK_ID.matches(id)) { "Invalid voice pack id" }
        val type = manifest.optString("type")
        val layouts = REQUIRED_ROLES[type] ?: throw IllegalArgumentException("Unsupported model type: $type")
        val fileNames = manifest.optJSONObject("files") ?: throw IllegalArgumentException("Missing files")
        val files = fileNames.keys().asSequence().associateWith { role ->
            val name = fileNames.optString(role)
            require(FILE_NAME.matches(name)) { "Invalid file name for $role" }
            File(directory, name)
        }
        require(layouts.any { files.keys.containsAll(it) }) { "Missing model files for $type" }
        val optionsJson = manifest.optJSONObject("options")
        val options = optionsJson?.keys()?.asSequence()?.associateWith { optionsJson.optString(it) }.orEmpty()
        val languages = manifest.optJSONArray("languages")
            ?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() } }
            .orEmpty()
        return VoicePack(
            id = id,
            name = manifest.optString("name").trim().ifEmpty { id }.take(64),
            version = manifest.optInt("version", 1),
            languages = languages,
            type = type,
            files = files,
            options = options,
            directory = directory
        )
    }

    private fun load(directory: File): VoicePack {
        val manifest = File(directory, MANIFEST)
        require(manifest.isFile && manifest.length() <= MAX_MANIFEST_BYTES) { "Missing manifest.json" }
        val pack = parseManifest(manifest.readText(Charsets.UTF_8), directory)
        pack.files.forEach { (role, file) -> require(file.isFile) { "Missing $role file ${file.name}" } }
        return pack
    }

    /** Extracts a flat pack, tolerating one wrapping folder (zipping a model directory). */
    private fun extract(input: InputStream, target: File) {
        var entries = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = flatName(entry.name) ?: continue
                if (!FILE_NAME.matches(name)) continue
                require(++entries <= MAX_ENTRIES) { "Too many files in voice pack" }
                val file = File(target, name)
                require(!file.exists()) { "Duplicate file $name" }
                file.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_TOTAL_BYTES) { "Voice pack is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    /** "manifest.json" or "folder/manifest.json" → "manifest.json"; deeper paths are ignored. */
    private fun flatName(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2 || parts.any { it == ".." || it == "." }) return null
        if (parts.first().startsWith("__MACOSX")) return null
        return parts.last()
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximum) throw IOException("Manifest is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun root(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }
}
