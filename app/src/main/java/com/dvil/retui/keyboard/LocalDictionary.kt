package com.dvil.retui.keyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.Locale
import java.util.zip.GZIPInputStream

object LocalDictionary {
    private const val KEY_WORDS_JSON = "dictionary.words.v1"
    private const val EXPORT_VERSION = 1
    private const val MAX_WORDS = 1200
    private const val MAX_WORD_LENGTH = 32
    private const val TYPO_MIN_LENGTH = 4
    private const val LATINIME_WORDLIST_GZIP_ASSET = "latinime/en_US_wordlist.combined.gz"
    private const val LATINIME_WORDLIST_PLAIN_ASSET = "latinime/en_US_wordlist.combined"
    private const val MAX_LATINIME_WORDS = 50_000
    private const val PREFIX_INDEX_DEPTH = 3

    private val builtInWords = listOf(
        "i", "the", "and", "you", "that", "have", "for", "not", "with", "this", "but", "from",
        "they", "say", "her", "she", "will", "one", "all", "would", "there", "their",
        "what", "about", "which", "when", "make", "can", "like", "time", "just", "know",
        "take", "people", "into", "year", "your", "good", "some", "could", "them", "see",
        "other", "than", "then", "now", "look", "only", "come", "its", "over", "think",
        "also", "back", "after", "use", "two", "how", "our", "work", "first", "well",
        "way", "even", "new", "want", "because", "any", "these", "give", "day", "most",
        "are", "was", "were", "been", "being", "does", "done", "did", "had", "has",
        "above", "actually", "again", "against", "almost", "already", "always",
        "android", "another", "answer", "anything", "around", "backup", "before",
        "between", "better", "build", "calendar", "called", "change", "check", "command", "config",
        "custom", "daily", "delete", "device", "dictionary", "during",
        "email", "enough", "error", "every", "feedback", "field", "file", "great",
        "hello", "height", "help", "home", "input", "issue", "keyboard", "launcher", "layout",
        "local", "looks", "margin", "message", "mobile", "morning", "music", "needs", "never", "night", "notes",
        "nothing", "number", "office", "offline", "open", "output", "phone", "please", "preview", "profile",
        "progress", "quick", "really", "restore", "right", "screen", "search", "send", "settings",
        "should", "space", "still", "storage", "store", "suggest", "system", "terminal", "thanks",
        "test", "tested", "tester", "testing", "tests", "text", "thing", "timer", "today", "toggle", "touch", "typing", "update", "user", "using",
        "value", "vibrate", "where", "while", "word", "words", "working", "world",
        "i'm", "i'd", "i'll", "i've", "can't", "couldn't", "didn't", "doesn't", "don't", "hadn't",
        "hasn't", "haven't", "isn't", "it's", "let's", "shouldn't", "that's", "there's", "they're",
        "wasn't", "we're", "weren't", "what's", "won't", "wouldn't", "you're"
    ).mapNotNull { normalizeWord(it) }.distinct()

    private val builtInSet = builtInWords.toHashSet()
    private val builtInWeight = builtInWords.mapIndexed { index, word ->
        word to (100_000 - (index * 240)).coerceAtLeast(8_000)
    }.toMap()
    private val fallbackStaticEntries = builtInWords.map { word ->
        StaticWordEntry(word, builtInWeight[word] ?: 8_000)
    }
    private val fallbackStaticIndex by lazy { buildStaticIndex(fallbackStaticEntries) }
    @Volatile private var latinImeLoaded = false
    @Volatile private var latinImeStaticSet: Set<String>? = null
    @Volatile private var latinImeStaticIndex: StaticIndex? = null
    @Volatile private var cachedUserWordsRaw: String? = null
    @Volatile private var cachedUserWords: List<UserWordEntry> = emptyList()

    private val contractionShortcuts = mapOf(
        "im" to "i'm",
        "id" to "i'd",
        "ill" to "i'll",
        "ive" to "i've",
        "cant" to "can't",
        "couldnt" to "couldn't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "dont" to "don't",
        "hadnt" to "hadn't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "isnt" to "isn't",
        "its" to "it's",
        "lets" to "let's",
        "shouldnt" to "shouldn't",
        "thats" to "that's",
        "theres" to "there's",
        "theyre" to "they're",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "whats" to "what's",
        "wont" to "won't",
        "wouldnt" to "wouldn't",
        "youre" to "you're"
    )
    private val staticBigrams = mapOf(
        "good" to listOf("morning", "luck", "job", "night"),
        "thank" to listOf("you"),
        "thanks" to listOf("for"),
        "how" to listOf("are"),
        "are" to listOf("you"),
        "i'm" to listOf("going", "not", "still"),
        "i'll" to listOf("check", "send", "update"),
        "i" to listOf("am", "have", "will"),
        "we" to listOf("can", "should", "will"),
        "you" to listOf("can", "should", "will"),
        "going" to listOf("to"),
        "the" to listOf("settings", "keyboard", "launcher"),
        "open" to listOf("settings", "keyboard", "launcher"),
        "check" to listOf("the", "this"),
        "send" to listOf("this", "the"),
        "update" to listOf("the", "this")
    ).mapKeys { normalizeWord(it.key) ?: it.key }
        .mapValues { entry -> entry.value.mapNotNull { normalizeWord(it) } }

    fun preload(context: Context) {
        if (latinImeLoaded) return
        synchronized(this) {
            if (latinImeLoaded) return
            val entries = loadLatinImeEntries(context)
            if (entries.isNotEmpty()) {
                latinImeStaticSet = entries.mapTo(HashSet(entries.size)) { it.word }
                latinImeStaticIndex = buildStaticIndex(entries)
            }
            latinImeLoaded = true
        }
    }

    fun suggest(prefs: SharedPreferences, rawPrefix: String, limit: Int): List<String> {
        val safeLimit = limit.coerceIn(1, 8)
        val prefix = normalizeSearch(rawPrefix)
        if (prefix.isBlank()) return emptyList()
        val searchPrefix = searchKey(prefix)
        val userWords = readEntries(prefs)
        val ranked = LinkedHashMap<String, RankedCandidate>()

        fun offer(word: String, score: Int) {
            val normalized = normalizeWord(word) ?: return
            if (normalized == prefix) return
            val current = ranked[normalized]
            if (current == null || score > current.score) {
                ranked[normalized] = RankedCandidate(normalized, score)
            }
        }

        val shortcut = if (prefix.contains('\'')) null else contractionShortcuts[searchPrefix]
        shortcut?.let { offer(it, 220_000) }

        staticPrefixCandidates(searchPrefix).forEach { entry ->
            val word = entry.word
            if (matchesPrefix(entry, prefix, searchPrefix)) {
                val score = builtInPrefixScore(entry, prefix, searchPrefix)
                offer(word, score)
            }
        }

        userWords.forEach { entry ->
            if (matchesPrefix(entry.word, prefix, searchPrefix) && (searchPrefix.length >= 3 || entry.frequency >= 3)) {
                val score = userPrefixScore(entry, prefix, searchPrefix)
                offer(entry.word, score)
            }
        }

        if (searchPrefix.length >= TYPO_MIN_LENGTH && ranked.size < safeLimit) {
            addTypoCandidates(searchPrefix, userWords) { word, score -> offer(word, score) }
        }

        return ranked.values
            .sortedWith(compareByDescending<RankedCandidate> { it.score }.thenBy { it.word })
            .take(safeLimit)
            .map { formatSuggestion(rawPrefix, it.word) }
    }

    fun suggestNextWords(prefs: SharedPreferences, rawPreviousWord: String?, limit: Int): List<String> {
        val previous = normalizeWord(rawPreviousWord ?: return emptyList()) ?: return emptyList()
        val safeLimit = limit.coerceIn(1, 5)
        val ranked = LinkedHashMap<String, RankedCandidate>()

        fun offer(word: String, score: Int) {
            val normalized = normalizeWord(word) ?: return
            if (normalized == previous) return
            val current = ranked[normalized]
            if (current == null || score > current.score) {
                ranked[normalized] = RankedCandidate(normalized, score)
            }
        }

        staticBigrams[previous].orEmpty().forEachIndexed { index, word ->
            offer(word, 120_000 - (index * 6_000))
        }
        readEntries(prefs)
            .filter { it.frequency >= 3 }
            .take(3)
            .forEach { entry -> offer(entry.word, 38_000 + (entry.frequency * 450).coerceAtMost(18_000)) }

        return ranked.values
            .sortedWith(compareByDescending<RankedCandidate> { it.score }.thenBy { it.word })
            .take(safeLimit)
            .map { displayWord(it.word) }
    }

    fun learnTypedWord(prefs: SharedPreferences, rawWord: String, force: Boolean = false): Boolean {
        val word = normalizeWord(rawWord) ?: return false
        val entries = readEntries(prefs).associateBy { it.word }.toMutableMap()
        val now = System.currentTimeMillis()
        val current = entries[word]
        if (current == null && (!force || isStaticWord(word))) return false
        entries[word] = if (current == null) {
            UserWordEntry(word = word, frequency = 1, lastUsedAt = now)
        } else {
            current.copy(frequency = (current.frequency + 1).coerceAtMost(Int.MAX_VALUE), lastUsedAt = now)
        }
        writeEntries(prefs, entries.values)
        return true
    }

    fun recordAcceptedWord(prefs: SharedPreferences, rawWord: String) {
        val word = normalizeWord(rawWord) ?: return
        val entries = readEntries(prefs).associateBy { it.word }.toMutableMap()
        val current = entries[word] ?: return
        entries[word] = current.copy(
            frequency = (current.frequency + 1).coerceAtMost(Int.MAX_VALUE),
            lastUsedAt = System.currentTimeMillis()
        )
        writeEntries(prefs, entries.values)
    }

    fun removeWord(prefs: SharedPreferences, rawWord: String): Boolean {
        val word = normalizeWord(rawWord) ?: return false
        val next = readEntries(prefs).filterNot { it.word == word }
        writeEntries(prefs, next)
        return true
    }

    fun hasUserWord(prefs: SharedPreferences, rawWord: String): Boolean {
        val word = normalizeWord(rawWord) ?: return false
        return readEntries(prefs).any { it.word == word }
    }

    fun isBuiltInWord(rawWord: String): Boolean {
        val word = normalizeWord(rawWord) ?: return false
        return isStaticWord(word)
    }

    fun containsKnownWord(prefs: SharedPreferences, rawWord: String): Boolean {
        return hasUserWord(prefs, rawWord) || isBuiltInWord(rawWord)
    }

    fun userWords(prefs: SharedPreferences): List<UserWordEntry> {
        return readEntries(prefs).sortedWith(
            compareBy<UserWordEntry> { it.word }.thenByDescending { it.frequency }
        )
    }

    fun editableText(prefs: SharedPreferences): String {
        return userWords(prefs).joinToString(separator = "\n") { displayWord(it.word) }
    }

    fun replaceUserWords(prefs: SharedPreferences, rawText: String): ImportResult {
        val current = readEntries(prefs).associateBy { it.word }
        val now = System.currentTimeMillis()
        val words = rawText
            .split(Regex("[,\\s]+"))
            .mapNotNull { normalizeWord(it) }
            .filterNot { isStaticWord(it) }
            .distinct()
        val entries = words.map { word ->
            current[word] ?: UserWordEntry(word = word, frequency = 1, lastUsedAt = now)
        }
        writeEntries(prefs, entries)
        return ImportResult(entries.size)
    }

    fun exportJson(prefs: SharedPreferences): String {
        val words = JSONArray()
        readEntries(prefs).sortedBy { it.word }.forEach { entry ->
            words.put(
                JSONObject()
                    .put("word", entry.word)
                    .put("frequency", entry.frequency)
                    .put("lastUsedAt", entry.lastUsedAt)
            )
        }
        return JSONObject()
            .put("version", EXPORT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("words", words)
            .toString(2)
    }

    fun importJson(prefs: SharedPreferences, rawJson: String): ImportResult {
        val imported = parseBackup(rawJson)
        writeEntries(prefs, imported)
        return ImportResult(imported.size)
    }

    private fun loadLatinImeEntries(context: Context): List<StaticWordEntry> {
        val out = LinkedHashMap<String, Int>()
        return try {
            val reader = openLatinImeReader(context) ?: return emptyList()
            reader.use {
                for (line in it.lineSequence()) {
                    if (out.size >= MAX_LATINIME_WORDS) break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("word=")) continue
                    if (trimmed.contains("not_a_word=true") || trimmed.contains("possibly_offensive=true")) continue
                    val rawWord = combinedField(trimmed, "word") ?: continue
                    val frequency = combinedField(trimmed, "f")?.toIntOrNull() ?: continue
                    if (frequency <= 0) continue
                    val word = normalizeWord(rawWord) ?: continue
                    val weight = latinImeWeight(frequency)
                    val current = out[word]
                    if (current == null || weight > current) {
                        out[word] = weight
                    }
                }
            }
            out.map { StaticWordEntry(it.key, it.value) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun openLatinImeReader(context: Context): BufferedReader? {
        try {
            val raw = context.assets.open(LATINIME_WORDLIST_GZIP_ASSET)
            return try {
                GZIPInputStream(raw).bufferedReader(Charsets.UTF_8)
            } catch (_: Exception) {
                raw.close()
                null
            }
        } catch (_: Exception) {
        }

        return try {
            context.assets.open(LATINIME_WORDLIST_PLAIN_ASSET).bufferedReader(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun combinedField(line: String, key: String): String? {
        val prefix = "$key="
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val valueStart = start + prefix.length
        val valueEnd = line.indexOf(',', valueStart).let { if (it < 0) line.length else it }
        return line.substring(valueStart, valueEnd).takeIf { it.isNotBlank() }
    }

    private fun latinImeWeight(frequency: Int): Int {
        return (frequency.coerceIn(1, 255) * 720).coerceIn(8_000, 180_000)
    }

    fun normalizeWord(rawWord: String): String? {
        val trimmed = normalizeApostrophes(rawWord).trim().trim('\'').lowercase(Locale.US)
        if (trimmed == "i") return trimmed
        if (trimmed.length < 2 || trimmed.length > MAX_WORD_LENGTH) return null
        if (!trimmed.any { it.isLetter() }) return null
        if (!trimmed.all { it.isLetter() || it == '\'' }) return null
        return trimmed
    }

    fun displayWord(rawWord: String): String {
        val word = normalizeWord(rawWord) ?: return rawWord.trim()
        return when (word) {
            "i'm" -> "I'm"
            "i'd" -> "I'd"
            "i'll" -> "I'll"
            "i've" -> "I've"
            else -> word
        }
    }

    fun isWordChar(char: Char): Boolean {
        return char.isLetter() || normalizeApostrophe(char) == '\''
    }

    private fun normalizeSearch(rawPrefix: String): String {
        return normalizeApostrophes(rawPrefix).trim().lowercase(Locale.US).filter { it.isLetter() || it == '\'' }
    }

    private fun matchesPrefix(word: String, prefix: String, searchPrefix: String): Boolean {
        if (prefix.isBlank()) return true
        return word.startsWith(prefix) || searchKey(word).startsWith(searchPrefix)
    }

    private fun matchesPrefix(entry: StaticWordEntry, prefix: String, searchPrefix: String): Boolean {
        if (prefix.isBlank()) return true
        return entry.word.startsWith(prefix) || entry.searchKey.startsWith(searchPrefix)
    }

    private fun builtInPrefixScore(entry: StaticWordEntry, prefix: String, searchPrefix: String): Int {
        val word = entry.word
        val base = entry.weight
        val matchBoost = if (word.startsWith(prefix)) 28_000 else 18_000
        val lengthPenalty = ((entry.searchKey.length - searchPrefix.length).coerceAtLeast(0) * 700).coerceAtMost(12_000)
        val shortPrefixBoost = if (searchPrefix.length < 3) 35_000 else 0
        return base + matchBoost + shortPrefixBoost - lengthPenalty
    }

    private fun userPrefixScore(entry: UserWordEntry, prefix: String, searchPrefix: String): Int {
        val base = if (searchPrefix.length < 3) 45_000 else 92_000
        val matchBoost = if (entry.word.startsWith(prefix)) 18_000 else 11_000
        val frequencyBoost = (entry.frequency * 1_800).coerceAtMost(42_000)
        val lengthPenalty = ((searchKey(entry.word).length - searchPrefix.length).coerceAtLeast(0) * 500).coerceAtMost(8_000)
        return base + matchBoost + frequencyBoost - lengthPenalty
    }

    private fun isStaticWord(word: String): Boolean {
        return latinImeStaticSet?.contains(word) == true || builtInSet.contains(word)
    }

    private fun staticIndex(): StaticIndex {
        return latinImeStaticIndex ?: fallbackStaticIndex
    }

    private fun staticPrefixCandidates(searchPrefix: String): List<StaticWordEntry> {
        val key = searchPrefix.take(PREFIX_INDEX_DEPTH)
        return staticIndex().prefixBuckets[key].orEmpty()
    }

    private fun staticTypoCandidates(searchPrefix: String, maxDistance: Int): Sequence<StaticWordEntry> {
        val buckets = staticIndex().lengthBuckets
        return ((searchPrefix.length - maxDistance)..(searchPrefix.length + maxDistance))
            .asSequence()
            .flatMap { length -> buckets[length].orEmpty().asSequence() }
    }

    private fun addTypoCandidates(
        searchPrefix: String,
        userWords: List<UserWordEntry>,
        offer: (String, Int) -> Unit
    ) {
        val maxDistance = if (searchPrefix.length >= 6) 2 else 1
        staticTypoCandidates(searchPrefix, maxDistance).forEach { entry ->
            val word = entry.word
            val compact = entry.searchKey
            if (kotlin.math.abs(compact.length - searchPrefix.length) <= maxDistance) {
                val distance = editDistanceAtMost(searchPrefix, compact, maxDistance)
                if (distance in 0..maxDistance) {
                    offer(word, 42_000 + (entry.weight / 10) - (distance * 12_000))
                }
            }
        }
        userWords
            .filter { it.frequency >= 2 }
            .forEach { entry ->
                val compact = searchKey(entry.word)
                if (kotlin.math.abs(compact.length - searchPrefix.length) <= maxDistance) {
                    val distance = editDistanceAtMost(searchPrefix, compact, maxDistance)
                    if (distance in 0..maxDistance) {
                        offer(entry.word, 58_000 + (entry.frequency * 1_000).coerceAtMost(18_000) - (distance * 12_000))
                    }
                }
            }
    }

    private fun editDistanceAtMost(left: String, right: String, maxDistance: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > maxDistance) return maxDistance + 1
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost
                )
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun searchKey(word: String): String {
        return word.filterNot { it == '\'' }
    }

    private fun buildStaticIndex(entries: List<StaticWordEntry>): StaticIndex {
        val prefixBuckets = HashMap<String, MutableList<StaticWordEntry>>()
        val lengthBuckets = HashMap<Int, MutableList<StaticWordEntry>>()
        entries.forEach { entry ->
            val key = entry.searchKey
            if (key.isNotBlank()) {
                val maxDepth = minOf(PREFIX_INDEX_DEPTH, key.length)
                for (length in 1..maxDepth) {
                    prefixBuckets.getOrPut(key.substring(0, length)) { mutableListOf() }.add(entry)
                }
                lengthBuckets.getOrPut(key.length) { mutableListOf() }.add(entry)
            }
        }
        return StaticIndex(
            prefixBuckets = prefixBuckets.mapValues { it.value.toList() },
            lengthBuckets = lengthBuckets.mapValues { it.value.toList() }
        )
    }

    private fun normalizeApostrophes(value: String): String {
        return value.map { normalizeApostrophe(it) }.joinToString(separator = "")
    }

    private fun normalizeApostrophe(char: Char): Char {
        return when (char) {
            '\u2018', '\u2019', '\u02bc', '\uff07', '`' -> '\''
            else -> char
        }
    }

    private fun readEntries(prefs: SharedPreferences): List<UserWordEntry> {
        val raw = prefs.getString(KEY_WORDS_JSON, null)
        if (raw != null && raw == cachedUserWordsRaw) return cachedUserWords
        if (raw == null) {
            cacheUserWords(null, emptyList())
            return emptyList()
        }
        val parsed = try {
            parseStoredArray(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
        cacheUserWords(raw, parsed)
        return parsed
    }

    private fun writeEntries(prefs: SharedPreferences, entries: Collection<UserWordEntry>) {
        val bounded = entries
            .asSequence()
            .filter { normalizeWord(it.word) != null }
            .sortedWith(compareByDescending<UserWordEntry> { it.frequency }.thenByDescending { it.lastUsedAt })
            .take(MAX_WORDS)
            .sortedBy { it.word }
            .toList()

        val array = JSONArray()
        bounded.forEach { entry ->
            array.put(
                JSONObject()
                    .put("word", entry.word)
                    .put("frequency", entry.frequency.coerceAtLeast(1))
                    .put("lastUsedAt", entry.lastUsedAt.coerceAtLeast(0L))
            )
        }
        val raw = array.toString()
        cacheUserWords(raw, bounded)
        prefs.edit().putString(KEY_WORDS_JSON, raw).apply()
    }

    private fun cacheUserWords(raw: String?, entries: List<UserWordEntry>) {
        cachedUserWordsRaw = raw
        cachedUserWords = entries
    }

    private fun parseBackup(rawJson: String): List<UserWordEntry> {
        val trimmed = rawJson.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("words") ?: JSONArray()
        }
        return parseStoredArray(array)
    }

    private fun parseStoredArray(array: JSONArray): List<UserWordEntry> {
        val entries = LinkedHashMap<String, UserWordEntry>()
        val now = System.currentTimeMillis()
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            val word = when (item) {
                is JSONObject -> item.optString("word", "")
                is String -> item
                else -> ""
            }
            val normalized = normalizeWord(word) ?: continue
            val frequency = if (item is JSONObject) item.optInt("frequency", 1) else 1
            val lastUsedAt = if (item is JSONObject) item.optLong("lastUsedAt", now) else now
            entries[normalized] = UserWordEntry(
                word = normalized,
                frequency = frequency.coerceAtLeast(1),
                lastUsedAt = lastUsedAt.coerceAtLeast(0L)
            )
        }
        return entries.values
            .sortedWith(compareByDescending<UserWordEntry> { it.frequency }.thenByDescending { it.lastUsedAt })
            .take(MAX_WORDS)
    }

    private fun applyPrefixCase(rawPrefix: String, word: String): String {
        if (rawPrefix.isBlank()) return word
        return when {
            rawPrefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.US)
            rawPrefix.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }
            else -> word
        }
    }

    private fun formatSuggestion(rawPrefix: String, word: String): String {
        val display = displayWord(word)
        if (rawPrefix.isBlank()) return display
        if (rawPrefix.all { !it.isLetter() || it.isUpperCase() }) return display.uppercase(Locale.US)
        if (word.startsWith("i'")) return display
        return applyPrefixCase(rawPrefix, display)
    }
}

data class UserWordEntry(
    val word: String,
    val frequency: Int,
    val lastUsedAt: Long
)

private data class RankedCandidate(
    val word: String,
    val score: Int
)

private data class StaticWordEntry(
    val word: String,
    val weight: Int,
    val searchKey: String = word.filterNot { it == '\'' }
)

private data class StaticIndex(
    val prefixBuckets: Map<String, List<StaticWordEntry>>,
    val lengthBuckets: Map<Int, List<StaticWordEntry>>
)

data class ImportResult(
    val wordCount: Int
)
