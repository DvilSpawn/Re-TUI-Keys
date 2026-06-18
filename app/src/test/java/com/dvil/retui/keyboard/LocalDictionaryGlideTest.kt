package com.dvil.retui.keyboard

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDictionaryGlideTest {
    @Test
    fun glideTraceRanksBuiltFirstFromLocalWords() {
        val prefs = prefsWithWords("built", "build", "bolt")

        val suggestions = LocalDictionary.suggestGlide(prefs, "buiilt", 3)

        assertEquals("built", suggestions.firstOrNull())
    }

    @Test
    fun glideTraceRanksCommonFallbackWords() {
        val prefs = prefsWithWords()

        assertEquals("hello", LocalDictionary.suggestGlide(prefs, "helo", 3).firstOrNull())
        assertTrue(LocalDictionary.suggestGlide(prefs, "keybord", 3).contains("keyboard"))
        assertTrue(LocalDictionary.suggestGlide(prefs, "thnks", 3).contains("thanks"))
    }

    @Test
    fun shortGlideTracePrefersShortEndpointWord() {
        val prefs = prefsWithWords()

        val suggestions = LocalDictionary.suggestGlide(prefs, "hui", 3)

        assertEquals("hi", suggestions.firstOrNull())
        assertTrue("hijacking should not beat a short endpoint trace", !suggestions.contains("hijacking"))
    }

    @Test
    fun geometryGlideRanksHiFromPathCrossingU() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()
        val path = listOf(
            centers.getValue('h'),
            GlidePoint(centers.getValue('u').x, centers.getValue('u').y + 18f),
            centers.getValue('i')
        )

        val suggestions = LocalDictionary.suggestGlideGeometry(prefs, path, centers, "hui", 3)

        assertEquals("hi", suggestions.firstOrNull())
    }

    @Test
    fun geometryGlideRanksBuiltFromKeyCenterPath() {
        val prefs = prefsWithWords("built", "build", "bolt")
        val centers = qwertyCenters()
        val path = "built".map { centers.getValue(it) }

        val suggestions = LocalDictionary.suggestGlideGeometry(prefs, path, centers, "built", 3)

        assertEquals("built", suggestions.firstOrNull())
    }

    @Test
    fun geometryGlideRanksHowFromNoisyPassThroughTrace() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()
        val path = listOf(
            centers.getValue('h'),
            centers.getValue('j'),
            centers.getValue('i'),
            centers.getValue('o'),
            centers.getValue('i'),
            centers.getValue('u'),
            centers.getValue('y'),
            centers.getValue('t'),
            centers.getValue('r'),
            centers.getValue('e'),
            centers.getValue('w')
        )

        val suggestions = LocalDictionary.suggestGlideGeometry(prefs, path, centers, "hjiouytrew", 3)

        assertEquals("how", suggestions.firstOrNull())
    }

    @Test
    fun geometryGlideUsesPreviousWordsForPhraseContext() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()
        val path = "doing".map { centers.getValue(it) }

        val suggestions = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = path,
            keyCenters = centers,
            rawTrace = "doing",
            limit = 3,
            previousWords = listOf("how", "are", "you")
        )

        assertEquals("doing", suggestions.firstOrNull())
    }

    @Test
    fun geometryGlideContextDoesNotOverrideStartKey() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()
        val path = "things".map { centers.getValue(it) }

        val suggestions = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = path,
            keyCenters = centers,
            rawTrace = "things",
            limit = 5,
            previousWords = listOf("hi", "how", "are")
        )

        assertEquals("things", suggestions.firstOrNull())
        assertTrue("context should not force you for a t-starting swipe", !suggestions.contains("you"))
    }

    @Test
    fun geometryGlideContextPromotesYouFromExportedTrace() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()
        val path = "yuiuy".map { centers.getValue(it) }

        val suggestions = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = path,
            keyCenters = centers,
            rawTrace = "yuiuy",
            limit = 5,
            previousWords = listOf("hi", "how", "are")
        )

        assertEquals("you", suggestions.firstOrNull())
    }

    @Test
    fun geometryGlidePrefersCommonBuiltInPhraseWords() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()

        val willSuggestions = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = "wertyuiol".map { centers.getValue(it) },
            keyCenters = centers,
            rawTrace = "wertyuiol",
            limit = 5,
            previousWords = listOf("i")
        )
        val goodSuggestions = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = "ghuiuytfd".map { centers.getValue(it) },
            keyCenters = centers,
            rawTrace = "ghuiuytfd",
            limit = 5
        )

        assertEquals("will", willSuggestions.firstOrNull())
        assertEquals("good", goodSuggestions.firstOrNull())
    }

    @Test
    fun geometryGlideUsesContextWhenSwipeStartsOnNeighborKey() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()

        val doingSuggestions = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = "fghujiuyhbgfd".map { centers.getValue(it) },
            keyCenters = centers,
            rawTrace = "fghujiuyhbgfd",
            limit = 5,
            previousWords = listOf("what", "are", "you")
        )
        val nightSuggestions = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = "bhuytgfghgfr".map { centers.getValue(it) },
            keyCenters = centers,
            rawTrace = "bhuytgfghgfr",
            limit = 5,
            previousWords = listOf("good")
        )

        assertEquals("doing", doingSuggestions.firstOrNull())
        assertEquals("night", nightSuggestions.firstOrNull())
    }

    @Test
    fun geometryGlideRanksPhraseStartsFromExportedTrace() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()

        assertEquals(
            "thank",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "tghgfdsdcvbhj".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "tghgfdsdcvbhj",
                limit = 5
            ).firstOrNull()
        )
        assertEquals(
            "open",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "oiuytfdfgvb".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "oiuytfdfgvb",
                limit = 5
            ).firstOrNull()
        )
        assertEquals(
            "see",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "ser".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "ser",
                limit = 5
            ).firstOrNull()
        )
        assertEquals(
            "talk",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "trsasdfghjkjb".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "trsasdfghjkjb",
                limit = 5
            ).firstOrNull()
        )
    }

    @Test
    fun geometryGlideRanksLatestTrainingCorrections() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()

        assertEquals(
            "you",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "yuiuyt".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "yuiuyt",
                limit = 5,
                previousWords = listOf("how", "are")
            ).firstOrNull()
        )
        assertEquals(
            "morning",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "njiuhgfgvhyuhvgf".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "njiuhgfgvhyuhvgf",
                limit = 5,
                previousWords = listOf("good")
            ).firstOrNull()
        )
        assertEquals(
            "thank",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "tyhgfdsdfvbjk".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "tyhgfdsdfvbjk",
                limit = 5
            ).firstOrNull()
        )
        assertEquals(
            "it",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "iuygtrf".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "iuygtrf",
                limit = 5,
                previousWords = listOf("i", "will", "send")
            ).firstOrNull()
        )
    }

    @Test
    fun learnedWordsDoNotAppearAsUniversalNextWordSuggestions() {
        val prefs = prefsWithWords("asalamualaikum")
        repeat(6) {
            LocalDictionary.recordAcceptedWord(prefs, "asalamualaikum")
        }

        assertTrue(LocalDictionary.suggestNextWords(prefs, "hi", 5).isEmpty())
        assertTrue(!LocalDictionary.suggestNextWords(prefs, "good", 5).contains("asalamualaikum"))
    }

    @Test
    fun learnedWordsStillAppearForTypedPrefix() {
        val prefs = prefsWithWords("asalamualaikum")

        assertTrue(LocalDictionary.suggest(prefs, "asa", 5).contains("asalamualaikum"))
    }

    @org.junit.Ignore("Calibration fixture from a real session; use for offline tuning, not as a release gate.")
    @Test
    fun geometryGlideRanksPulledPhraseSession() {
        val prefs = prefsWithWords()
        val expected = listOf("hi", "how", "are", "you", "doing", "today")
        val rows = javaClass.classLoader!!
            .getResourceAsStream("glide-diagnostics/hi-how-are-you-doing-today.jsonl")!!
            .bufferedReader()
            .readLines()

        rows.zip(expected).forEach { (row, word) ->
            val entry = JSONObject(row)
            val suggestions = LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = jsonPoints(entry),
                keyCenters = jsonKeyCenters(entry),
                rawTrace = entry.getString("rawTrace"),
                limit = 5
            )

            assertEquals("rawTrace=${entry.getString("rawTrace")}", word, suggestions.firstOrNull())
        }
    }

    @Test
    fun shortOrMismatchedTraceReturnsNoSuggestions() {
        val prefs = prefsWithWords("built")

        assertTrue(LocalDictionary.suggestGlide(prefs, "b", 3).isEmpty())
        assertTrue(LocalDictionary.suggestGlide(prefs, "xuilt", 3).isEmpty())
    }

    private fun prefsWithWords(vararg words: String): SharedPreferences {
        return FakePrefs().also { prefs ->
            if (words.isNotEmpty()) {
                LocalDictionary.replaceUserWords(prefs, words.joinToString(separator = "\n"))
            }
        }
    }

    private fun qwertyCenters(): Map<Char, GlidePoint> {
        val rows = listOf(
            "qwertyuiop" to 0f,
            "asdfghjkl" to 50f,
            "zxcvbnm" to 100f
        )
        val centers = mutableMapOf<Char, GlidePoint>()
        rows.forEach { (row, y) ->
            val xOffset = when (row) {
                "asdfghjkl" -> 25f
                "zxcvbnm" -> 75f
                else -> 0f
            }
            row.forEachIndexed { index, char ->
                centers[char] = GlidePoint(xOffset + (index * 50f), y)
            }
        }
        return centers
    }

    private fun jsonPoints(entry: JSONObject): List<GlidePoint> {
        val points = entry.getJSONArray("points")
        return List(points.length()) { index ->
            val point = points.getJSONObject(index)
            GlidePoint(point.getDouble("x").toFloat(), point.getDouble("y").toFloat())
        }
    }

    private fun jsonKeyCenters(entry: JSONObject): Map<Char, GlidePoint> {
        val centers = entry.getJSONObject("keyCenters")
        return centers.keys().asSequence().associate { key ->
            val point = centers.getJSONObject(key)
            key.single() to GlidePoint(point.getDouble("x").toFloat(), point.getDouble("y").toFloat())
        }
    }

    private class FakePrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(private val values: MutableMap<String, Any>) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null && value != null) values[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) values.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { values.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
