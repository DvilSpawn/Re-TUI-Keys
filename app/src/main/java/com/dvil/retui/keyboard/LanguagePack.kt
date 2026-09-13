package com.dvil.retui.keyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class LanguagePack(
    val id: String,
    val languageTag: String,
    val name: String,
    val nativeName: String,
    val switchLabel: String,
    val version: Int,
    val rtl: Boolean,
    val rows: List<List<String>>,
    val digits: List<String>,
    val comma: String,
    val period: String,
    val questionMark: String,
    val spaceLabel: String,
    val joinerLabel: String,
    val joiners: Set<Char>,
    val characterMap: Map<Char, String>,
    val stripMarks: Boolean,
    val casing: String,
    val gridRows: Boolean,
    internal val words: List<LanguagePackWord>
) {
    internal val locale = Locale.forLanguageTag(languageTag)

    /**
     * True when the layout is written in a bicameral script and therefore needs a SHIFT key.
     * Detected from the declared rows so pack authors do not have to flag it; `casing: "none"`
     * turns it off explicitly for caseless scripts such as Persian.
     */
    val bicameral: Boolean by lazy {
        casing != "none" && rows.any { row ->
            row.any { key -> key.uppercase(locale) != key.lowercase(locale) }
        }
    }

    private val wordSet by lazy { words.mapTo(HashSet(words.size)) { it.word } }
    private val prefixIndex by lazy {
        val buckets = HashMap<String, MutableList<LanguagePackWord>>()
        words.forEach { entry ->
            for (length in 1..minOf(3, entry.word.length)) {
                buckets.getOrPut(entry.word.substring(0, length)) { mutableListOf() }.add(entry)
            }
        }
        buckets.mapValues { it.value.toList() }
    }

    fun normalizeWord(rawWord: String): String? {
        val compatible = Normalizer.normalize(rawWord, Normalizer.Form.NFKC)
        val mapped = buildString(compatible.length) {
            compatible.forEach { char ->
                if (!stripMarks || Character.getType(char) != Character.NON_SPACING_MARK.toInt()) {
                    append(characterMap[char] ?: char.toString())
                }
            }
        }
        val cased = if (casing == "lower") mapped.lowercase(locale) else mapped
        val trimmed = cased.trim().trim { it in joiners }
        if (trimmed.isEmpty() || trimmed.length > MAX_WORD_LENGTH) return null
        if (!trimmed.any(Char::isLetter)) return null
        if (!trimmed.all(::isWordChar)) return null
        return trimmed
    }

    fun isWordChar(char: Char): Boolean = char.isLetter() || char in joiners

    /** Shifted form of a layout key, using the pack locale so `lt`, `tr` and friends case correctly. */
    fun upperKey(key: String): String = if (bicameral) key.uppercase(locale) else key

    /** Columns a grid layout renders per row: the widest row, BACKSPACE included. */
    val gridColumns: Int
        get() = rows[0].size

    internal fun containsStatic(word: String): Boolean = word in wordSet

    internal fun staticSuggestions(prefix: String, limit: Int): List<String> {
        val key = prefix.take(minOf(3, prefix.length))
        return prefixIndex[key].orEmpty()
            .asSequence()
            .map(LanguagePackWord::word)
            .filter { it != prefix && it.startsWith(prefix) }
            .take(limit)
            .toList()
    }

    companion object {
        private const val MAX_WORD_LENGTH = 32
    }
}

/**
 * Width budget for the bottom letter row of a language pack. Packs may declare up to fourteen
 * letters per row, so SHIFT, the joiner and BACKSPACE have to give way instead of pushing the
 * letters off screen.
 */
internal object LanguagePackLayout {
    const val MAX_ROW_UNITS = 12f
    const val MIN_SPECIAL_UNITS = 0.8f

    fun specialWeights(letterCount: Int, requested: List<Float>): List<Float> {
        if (requested.isEmpty()) return requested
        val total = requested.sum()
        val allowed = maxOf(MAX_ROW_UNITS - letterCount, requested.size * MIN_SPECIAL_UNITS)
        if (total <= allowed) return requested
        val scale = allowed / total
        return requested.map { it * scale }
    }

    /** Columns per half of a split keyboard: the widest row decides, so the clusters stay aligned. */
    fun halfColumns(rowSizes: List<Int>): Int =
        rowSizes.filter { it > 0 }.maxOfOrNull { (it + 1) / 2 } ?: 1

    /** Where a row breaks into its left and right cluster. */
    fun splitIndex(columnCount: Int, halfColumns: Int): Int =
        minOf(halfColumns, (columnCount + 1) / 2)
}

internal data class LanguagePackInstallResult(
    val pack: LanguagePack? = null,
    val error: String? = null
)

internal data class LanguagePackWord(
    val word: String,
    val frequency: Int
)

internal object LanguagePackManager {
    const val ENGLISH_ID = "en-US"
    const val KEY_ACTIVE_LANGUAGE = "language.active"

    private const val DIRECTORY = "language-packs"
    private const val EXTENSION = ".retui-lang"
    private const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024

    fun activeId(prefs: SharedPreferences): String =
        prefs.getString(KEY_ACTIVE_LANGUAGE, ENGLISH_ID).orEmpty().ifBlank { ENGLISH_ID }

    fun setActive(prefs: SharedPreferences, id: String) {
        prefs.edit().putString(KEY_ACTIVE_LANGUAGE, id).apply()
    }

    fun activePack(context: Context, prefs: SharedPreferences): LanguagePack? {
        val id = activeId(prefs)
        if (id == ENGLISH_ID) return null
        return load(context, id) ?: run {
            setActive(prefs, ENGLISH_ID)
            null
        }
    }

    fun installedPacks(context: Context): List<LanguagePack> {
        val files = packDirectory(context)
            .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            .orEmpty()
        pruneCache(files.mapTo(HashSet()) { it.absolutePath })
        return files
            .mapNotNull(::parseFile)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /**
     * Parses a pack archive, reusing the previous result while the file is untouched.
     *
     * The keyboard reloads its pack list on every `onStartInput` and `onStartInputView`, so this
     * runs twice each time the keyboard appears. Re-inflating and re-normalizing 50,000 words per
     * pack there is a visible stall, and it also threw away the lazy prefix index that powers
     * completions.
     */
    internal fun parseFile(file: java.io.File): LanguagePack? {
        val key = file.absolutePath
        val length = file.length()
        val modifiedAt = file.lastModified()
        cache[key]?.let { cached ->
            if (cached.length == length && cached.modifiedAt == modifiedAt) return cached.pack
        }
        if (length > MAX_ARCHIVE_BYTES) {
            cache.remove(key)
            return null
        }
        val pack = runCatching { LanguagePackArchive.parse(file.readBytes()) }.getOrNull()
        if (pack == null) {
            cache.remove(key)
            return null
        }
        cache[key] = CachedPack(length, modifiedAt, pack)
        return pack
    }

    private fun pruneCache(livePaths: Set<String>) {
        cache.keys.retainAll(livePaths)
    }

    internal fun clearCache() {
        cache.clear()
    }

    private class CachedPack(
        val length: Long,
        val modifiedAt: Long,
        val pack: LanguagePack
    )

    private val cache = HashMap<String, CachedPack>()

    fun install(context: Context, input: InputStream): LanguagePackInstallResult {
        return try {
            val bytes = readBounded(input, MAX_ARCHIVE_BYTES)
            val pack = LanguagePackArchive.parse(bytes)
            val directory = packDirectory(context)
            check(directory.exists() || directory.mkdirs()) { "Cannot create language pack storage" }
            val target = java.io.File(directory, pack.id + EXTENSION)
            val pending = java.io.File(directory, ".${pack.id}.pending")
            pending.writeBytes(bytes)
            try {
                Files.move(
                    pending.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            cache[target.absolutePath] = CachedPack(target.length(), target.lastModified(), pack)
            LanguagePackInstallResult(pack = pack)
        } catch (error: Exception) {
            LanguagePackInstallResult(error = error.message ?: "Invalid language pack")
        }
    }

    fun delete(context: Context, prefs: SharedPreferences, id: String): Boolean {
        if (id == ENGLISH_ID || !PACK_ID.matches(id)) return false
        val file = java.io.File(packDirectory(context), id + EXTENSION)
        if (!file.isFile || !file.delete()) return false
        cache.remove(file.absolutePath)
        setActive(prefs, ENGLISH_ID)
        return true
    }

    private fun load(context: Context, id: String): LanguagePack? {
        if (!PACK_ID.matches(id)) return null
        val file = java.io.File(packDirectory(context), id + EXTENSION)
        return if (!file.isFile) null else parseFile(file)
    }

    private fun packDirectory(context: Context) = java.io.File(context.filesDir, DIRECTORY)

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maximum) { "Language pack is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    internal val PACK_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,31}")
}

internal object LanguagePackArchive {
    private const val SCHEMA = 1
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_WORDS_BYTES = 6 * 1024 * 1024
    private const val MAX_WORDS = 50_000

    fun parse(bytes: ByteArray): LanguagePack {
        var manifestBytes: ByteArray? = null
        var wordsBytes: ByteArray? = null
        val seen = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                require(seen.add(entry.name)) { "Duplicate language pack entry: ${entry.name}" }
                when (entry.name) {
                    "manifest.json" -> manifestBytes = readEntry(zip, MAX_MANIFEST_BYTES)
                    "words.tsv" -> wordsBytes = readEntry(zip, MAX_WORDS_BYTES)
                    "LICENSE", "NOTICE" -> readEntry(zip, MAX_MANIFEST_BYTES)
                    else -> error("Unexpected language pack entry: ${entry.name}")
                }
            }
        }

        val manifest = JSONObject(String(requireNotNull(manifestBytes) { "Missing manifest.json" }, Charsets.UTF_8))
        require(manifest.optInt("schema") == SCHEMA) { "Unsupported language pack schema" }
        val id = manifest.getString("id")
        require(LanguagePackManager.PACK_ID.matches(id)) { "Invalid language pack ID" }
        val languageTag = manifest.getString("languageTag")
        require(Locale.forLanguageTag(languageTag).language.isNotBlank()) { "Invalid language tag" }
        val direction = manifest.optString("direction", "ltr")
        require(direction == "ltr" || direction == "rtl") { "Invalid text direction" }
        val casing = manifest.optString("casing", "lower")
        require(casing == "lower" || casing == "none") { "Invalid casing mode" }
        val rowStyle = manifest.optString("rowStyle", "staggered")
        require(rowStyle == "staggered" || rowStyle == "grid") { "Invalid row style" }

        val rows = manifest.getJSONArray("rows").toStringRows()
        require(rows.size == 3) { "Language pack must contain three key rows" }
        require(rows.all { it.size in 6..14 }) { "Invalid language pack key row" }
        require(rows.flatten().all { it.isNotBlank() && it.length <= 4 }) { "Invalid language pack key" }
        if (rowStyle == "grid") {
            require(rows[0].size == rows[1].size && rows[2].size + 1 == rows[0].size) {
                "Grid rows must be equal width, with the last row reserving one key for backspace"
            }
        }

        val digits = manifest.optJSONArray("digits")?.toStringList().orEmpty()
        require(digits.isEmpty() || (digits.size == 10 && digits.all { it.length <= 2 })) {
            "Language pack digits must contain ten keys"
        }

        val joiners = manifest.optJSONArray("joiners")?.toStringList().orEmpty().map { value ->
            require(value.length == 1) { "Joiners must be one character" }
            value.single()
        }.toSet()
        val characterMap = buildMap<Char, String> {
            val source = manifest.optJSONObject("characterMap") ?: JSONObject()
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = source.getString(key)
                require(key.length == 1 && value.length <= 4) { "Invalid character map" }
                put(key.single(), value)
            }
        }

        val shell = LanguagePack(
            id = id,
            languageTag = languageTag,
            name = manifest.getString("name").boundedLabel("name"),
            nativeName = manifest.getString("nativeName").boundedLabel("nativeName"),
            switchLabel = manifest.getString("switchLabel").boundedLabel("switchLabel", 4),
            version = manifest.optInt("version", 1).also { require(it > 0) { "Invalid pack version" } },
            rtl = direction == "rtl",
            rows = rows,
            digits = digits,
            comma = manifest.optString("comma", ",").boundedKey("comma"),
            period = manifest.optString("period", ".").boundedKey("period"),
            questionMark = manifest.optString("questionMark", "?").boundedKey("questionMark"),
            spaceLabel = manifest.optString("spaceLabel", "SPACE").boundedLabel("spaceLabel", 10),
            joinerLabel = manifest.optString("joinerLabel", "JOIN").boundedLabel("joinerLabel", 8),
            joiners = joiners,
            characterMap = characterMap,
            stripMarks = manifest.optBoolean("stripMarks", false),
            casing = casing,
            gridRows = rowStyle == "grid",
            words = emptyList()
        )

        val words = String(requireNotNull(wordsBytes) { "Missing words.tsv" }, Charsets.UTF_8)
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val separator = trimmed.lastIndexOf('\t')
                val rawWord = if (separator >= 0) trimmed.substring(0, separator) else trimmed
                val frequency = if (separator >= 0) trimmed.substring(separator + 1).toIntOrNull() ?: 1 else 1
                shell.normalizeWord(rawWord)?.let { LanguagePackWord(it, frequency.coerceIn(1, 255)) }
            }
            .distinctBy(LanguagePackWord::word)
            .sortedWith(compareByDescending<LanguagePackWord> { it.frequency }.thenBy { it.word })
            .take(MAX_WORDS + 1)
            .toList()
        require(words.size in 10..MAX_WORDS) { "Language pack word list must contain 10-$MAX_WORDS words" }
        return shell.copy(words = words)
    }

    private fun readEntry(zip: ZipInputStream, maximum: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maximum) { "Language pack entry is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun JSONArray.toStringRows(): List<List<String>> =
        List(length()) { index -> getJSONArray(index).toStringList() }

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { index -> getString(index) }

    private fun String.boundedLabel(field: String, maximum: Int = 32): String {
        val value = trim()
        require(value.isNotEmpty() && value.length <= maximum) { "Invalid $field" }
        return value
    }

    private fun String.boundedKey(field: String): String {
        require(isNotEmpty() && length <= 4) { "Invalid $field" }
        return this
    }
}

internal object LanguagePackDictionary {
    private const val MAX_USER_WORDS = 1200
    private val cache = HashMap<String, Pair<String?, List<UserWordEntry>>>()

    fun normalizeWord(pack: LanguagePack, rawWord: String): String? = pack.normalizeWord(rawWord)

    fun containsKnownWord(pack: LanguagePack, prefs: SharedPreferences, rawWord: String): Boolean {
        val word = pack.normalizeWord(rawWord) ?: return false
        return pack.containsStatic(word) || readEntries(pack, prefs).any { it.word == word }
    }

    fun suggest(pack: LanguagePack, prefs: SharedPreferences, rawPrefix: String, limit: Int): List<String> {
        val prefix = pack.normalizeWord(rawPrefix) ?: return emptyList()
        val safeLimit = limit.coerceIn(1, 8)
        val out = LinkedHashSet<String>()
        readEntries(pack, prefs)
            .asSequence()
            .filter { it.word != prefix && it.word.startsWith(prefix) }
            .sortedWith(compareByDescending<UserWordEntry> { it.frequency }.thenByDescending { it.lastUsedAt })
            .forEach { out.add(it.word) }
        pack.staticSuggestions(prefix, safeLimit * 2).forEach(out::add)
        return out.take(safeLimit)
    }

    fun suggestCurrentWordAlternatives(
        pack: LanguagePack,
        prefs: SharedPreferences,
        rawWord: String,
        limit: Int
    ): List<String> {
        val word = pack.normalizeWord(rawWord) ?: return emptyList()
        return if (containsKnownWord(pack, prefs, word) && limit > 0) listOf(word) else emptyList()
    }

    fun learnTypedWord(pack: LanguagePack, prefs: SharedPreferences, rawWord: String, force: Boolean): Boolean {
        val word = pack.normalizeWord(rawWord) ?: return false
        val entries = readEntries(pack, prefs).associateBy { it.word }.toMutableMap()
        val current = entries[word]
        if (current == null && (!force || pack.containsStatic(word))) return false
        val now = System.currentTimeMillis()
        entries[word] = current?.copy(
            frequency = (current.frequency + 1).coerceAtMost(Int.MAX_VALUE),
            lastUsedAt = now
        ) ?: UserWordEntry(word, 1, now)
        writeEntries(pack, prefs, entries.values)
        return true
    }

    fun recordAcceptedWord(pack: LanguagePack, prefs: SharedPreferences, rawWord: String) {
        val word = pack.normalizeWord(rawWord) ?: return
        val entries = readEntries(pack, prefs).associateBy { it.word }.toMutableMap()
        val current = entries[word] ?: return
        entries[word] = current.copy(
            frequency = (current.frequency + 1).coerceAtMost(Int.MAX_VALUE),
            lastUsedAt = System.currentTimeMillis()
        )
        writeEntries(pack, prefs, entries.values)
    }

    private fun key(pack: LanguagePack) = "language.words.${pack.id}"

    private fun readEntries(pack: LanguagePack, prefs: SharedPreferences): List<UserWordEntry> {
        val key = key(pack)
        val raw = prefs.getString(key, null)
        cache[key]?.takeIf { it.first == raw }?.let { return it.second }
        val parsed = try {
            val array = JSONArray(raw ?: "[]")
            List(array.length()) { index -> array.optJSONObject(index) }
                .mapNotNull { item ->
                    val word = pack.normalizeWord(item?.optString("word").orEmpty()) ?: return@mapNotNull null
                    UserWordEntry(
                        word,
                        item?.optInt("frequency", 1)?.coerceAtLeast(1) ?: 1,
                        item?.optLong("lastUsedAt", 0L)?.coerceAtLeast(0L) ?: 0L
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
        cache[key] = raw to parsed
        return parsed
    }

    private fun writeEntries(pack: LanguagePack, prefs: SharedPreferences, entries: Collection<UserWordEntry>) {
        val bounded = entries
            .filter { pack.normalizeWord(it.word) != null }
            .sortedWith(compareByDescending<UserWordEntry> { it.frequency }.thenByDescending { it.lastUsedAt })
            .take(MAX_USER_WORDS)
        val array = JSONArray()
        bounded.forEach { entry ->
            array.put(
                JSONObject()
                    .put("word", entry.word)
                    .put("frequency", entry.frequency)
                    .put("lastUsedAt", entry.lastUsedAt)
            )
        }
        val raw = array.toString()
        cache[key(pack)] = raw to bounded
        prefs.edit().putString(key(pack), raw).apply()
    }
}
