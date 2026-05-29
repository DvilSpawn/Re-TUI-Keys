package com.dvil.retui.keyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object LocalDictionary {
    private const val KEY_WORDS_JSON = "dictionary.words.v1"
    private const val EXPORT_VERSION = 1
    private const val MAX_WORDS = 1200
    private const val MAX_WORD_LENGTH = 32

    private val builtInWords = listOf(
        "about", "above", "after", "again", "against", "almost", "already", "also", "always",
        "android", "another", "answer", "anything", "around", "because", "before", "being",
        "between", "build", "calendar", "called", "change", "check", "command", "could",
        "daily", "delete", "device", "does", "done", "during", "email", "enough", "error",
        "every", "feedback", "field", "first", "from", "great", "hello", "help", "home",
        "input", "issue", "keyboard", "launcher", "layout", "local", "looks", "message",
        "mobile", "music", "needs", "never", "notes", "nothing", "number", "offline",
        "output", "phone", "please", "progress", "quick", "really", "restore", "right",
        "screen", "search", "send", "settings", "should", "space", "still", "storage",
        "suggest", "system", "terminal", "thanks", "that", "their", "there", "these",
        "thing", "think", "this", "timer", "today", "toggle", "typing", "update", "user",
        "using", "want", "what", "when", "where", "which", "while", "will", "with",
        "word", "words", "work", "world", "would", "your"
    )

    private val builtInSet = builtInWords.toHashSet()

    fun suggest(prefs: SharedPreferences, rawPrefix: String, limit: Int): List<String> {
        val safeLimit = limit.coerceIn(1, 8)
        val prefix = normalizeSearch(rawPrefix)
        val userWords = readEntries(prefs)

        val rankedUserWords = if (prefix.isBlank()) {
            userWords
                .sortedWith(compareByDescending<UserWordEntry> { it.lastUsedAt }.thenBy { it.word })
                .map { it.word }
        } else {
            userWords
                .asSequence()
                .filter { it.word.startsWith(prefix) && it.word != prefix }
                .sortedWith(
                    compareByDescending<UserWordEntry> { it.frequency }
                        .thenByDescending { it.lastUsedAt }
                        .thenBy { it.word }
                )
                .map { it.word }
                .toList()
        }

        val rankedBuiltIns = if (prefix.isBlank()) {
            emptyList()
        } else {
            builtInWords.filter { it.startsWith(prefix) && it != prefix }
        }

        return (rankedUserWords + rankedBuiltIns)
            .distinct()
            .take(safeLimit)
            .map { applyPrefixCase(rawPrefix, it) }
    }

    fun learnTypedWord(prefs: SharedPreferences, rawWord: String, force: Boolean = false): Boolean {
        val word = normalizeWord(rawWord) ?: return false
        if (!force && builtInSet.contains(word)) return false
        val entries = readEntries(prefs).associateBy { it.word }.toMutableMap()
        val now = System.currentTimeMillis()
        val current = entries[word]
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
        return builtInSet.contains(word)
    }

    fun userWords(prefs: SharedPreferences): List<UserWordEntry> {
        return readEntries(prefs).sortedWith(
            compareBy<UserWordEntry> { it.word }.thenByDescending { it.frequency }
        )
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

    fun normalizeWord(rawWord: String): String? {
        val trimmed = rawWord.trim().trim('\'').lowercase(Locale.US)
        if (trimmed.length < 2 || trimmed.length > MAX_WORD_LENGTH) return null
        if (!trimmed.any { it.isLetter() }) return null
        if (!trimmed.all { it.isLetter() || it == '\'' }) return null
        return trimmed
    }

    private fun normalizeSearch(rawPrefix: String): String {
        return rawPrefix.trim().lowercase(Locale.US).filter { it.isLetter() || it == '\'' }
    }

    private fun readEntries(prefs: SharedPreferences): List<UserWordEntry> {
        val raw = prefs.getString(KEY_WORDS_JSON, null) ?: return emptyList()
        return try {
            parseStoredArray(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
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
        prefs.edit().putString(KEY_WORDS_JSON, array.toString()).apply()
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
}

data class UserWordEntry(
    val word: String,
    val frequency: Int,
    val lastUsedAt: Long
)

data class ImportResult(
    val wordCount: Int
)
