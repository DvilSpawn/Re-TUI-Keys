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
    fun geometryGlideUsesShapeWhenTraceMissesLetters() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()
        val path = "custom".map { centers.getValue(it) }

        val suggestions = LocalDictionary.suggestGlideGeometry(prefs, path, centers, "cm", 3)

        assertEquals("custom", suggestions.firstOrNull())
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
    fun geometryGlideRanksLatestPhoneCorrections() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()

        assertEquals(
            "thanks",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "tyhgfdsdfcvbhjhgfd".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "tyhgfdsdfcvbhjhgfd",
                limit = 5
            ).firstOrNull()
        )
        assertEquals(
            "app",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "asdfghuio".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "asdfghuio",
                limit = 5,
                previousWords = listOf("the")
            ).firstOrNull()
        )
    }

    @Test
    fun geometryGlideRanksFutoCommonShortCorrections() {
        val prefs = prefsWithWords()
        val centers = qwertyCenters()

        assertEquals(
            "the",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "tyghgtre".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "tyghgtre",
                limit = 5
            ).firstOrNull()
        )
        assertEquals(
            "in",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "ijn".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "ijn",
                limit = 5
            ).firstOrNull()
        )
        assertEquals(
            "of",
            LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = "oiuytf".map { centers.getValue(it) },
                keyCenters = centers,
                rawTrace = "oiuytf",
                limit = 5
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

    @Test
    fun currentWordAlternativesDoNotIncludeLongerCompletions() {
        val prefs = prefsWithWords("william")

        val suggestions = LocalDictionary.suggestCurrentWordAlternatives(prefs, "Will", 3)

        assertEquals(listOf("Will", "Well"), suggestions.take(2))
        assertTrue(!suggestions.contains("William"))
    }

    @Test
    fun willPredictsCommonNextWords() {
        val prefs = prefsWithWords()

        assertEquals(listOf("you", "know"), LocalDictionary.suggestNextWords(prefs, "will", 2))
    }

    @Test
    fun acceptedWordsLearnBigramAndTrigramNextWords() {
        val prefs = prefsWithWords("alpha", "beta", "gamma")

        LocalDictionary.recordAcceptedWord(prefs, "beta", listOf("alpha"))
        LocalDictionary.recordAcceptedWord(prefs, "gamma", listOf("alpha", "beta"))

        assertEquals("beta", LocalDictionary.suggestNextWords(prefs, "alpha", 3).firstOrNull())
        assertEquals(
            "gamma",
            LocalDictionary.suggestNextWords(prefs, "beta", 3, listOf("alpha", "beta")).firstOrNull()
        )
    }

    @Test
    fun emailFieldCompletionsPreserveLocalPart() {
        assertEquals(
            "name@gmail.com",
            LocalDictionary.suggestFieldCompletions("name@gm", FieldCompletionKind.EMAIL, 5).firstOrNull()
        )
        assertEquals(
            "name@gmail.com",
            LocalDictionary.suggestFieldCompletions("name@", FieldCompletionKind.EMAIL, 5).firstOrNull()
        )
    }

    @Test
    fun urlFieldCompletionsOfferCommonSuffixes() {
        assertEquals(
            "example.com",
            LocalDictionary.suggestFieldCompletions("example", FieldCompletionKind.URL, 5).firstOrNull()
        )
        assertEquals(
            "example.com",
            LocalDictionary.suggestFieldCompletions("example.c", FieldCompletionKind.URL, 5).firstOrNull()
        )
    }

    @Test
    fun typedCorrectionPrefersNearbyQwertyKey() {
        val prefs = prefsWithWords("zoll", "zall")
        repeat(2) {
            LocalDictionary.recordAcceptedWord(prefs, "zoll")
            LocalDictionary.recordAcceptedWord(prefs, "zall")
        }

        assertEquals("zoll", LocalDictionary.suggest(prefs, "zill", 2).firstOrNull())
    }

    @Test
    fun typedCorrectionUsesTapPosition() {
        val prefs = prefsWithWords("zoll", "zall")
        repeat(2) {
            LocalDictionary.recordAcceptedWord(prefs, "zoll")
            LocalDictionary.recordAcceptedWord(prefs, "zall")
        }
        val centers = qwertyCenters()
        val taps = "zoll".map { centers.getValue(it) }

        assertEquals(
            "zoll",
            LocalDictionary.suggest(prefs, "zill", 2, tapPoints = taps, keyCenters = centers).firstOrNull()
        )
    }

    @Test
    fun tapOffsetsLearnAndShiftKeyCenters() {
        val prefs = FakeSharedPreferences()
        val centers = qwertyCenters()
        val o = centers.getValue('o')

        LocalDictionary.recordTapOffsets(
            prefs,
            samples = listOf(
                TapSample('o', GlidePoint(o.x - 20f, o.y)),
                TapSample('o', GlidePoint(o.x - 10f, o.y))
            ),
            keyCenters = centers
        )

        assertTrue(LocalDictionary.applyTapOffsets(prefs, centers).getValue('o').x < o.x)
    }

    @Test
    fun geometryGlideUsesLoopEvidenceForRepeatedLetter() {
        val prefs = prefsWithWords("god")
        val centers = qwertyCenters()
        val o = centers.getValue('o')
        val path = listOf(
            centers.getValue('g'),
            o,
            GlidePoint(o.x + 28f, o.y + 12f),
            GlidePoint(o.x - 22f, o.y + 10f),
            o,
            centers.getValue('d')
        )

        assertEquals("good", LocalDictionary.suggestGlideGeometry(prefs, path, centers, "god", 2).firstOrNull())
    }

    @Test
    fun geometryGlideKeepsExactShortWord() {
        val centers = qwertyCenters()
        val path = listOf(centers.getValue('g'), centers.getValue('o'))

        assertEquals(
            "go",
            LocalDictionary.suggestGlideGeometry(FakeSharedPreferences(), path, centers, "go", 2).firstOrNull()
        )
    }

    @Test
    fun geometryGlideKeepsExactLongWord() {
        val prefs = prefsWithWords("wonderful")
        val centers = qwertyCenters()
        val path = "wonderful".map { centers.getValue(it) }

        assertEquals(
            "wonderful",
            LocalDictionary.suggestGlideGeometry(prefs, path, centers, "wonderful", 2).firstOrNull()
        )
    }

    @Test
    fun glideTraceToleratesNeighborKeysOnLongWord() {
        val prefs = prefsWithWords("wonderful")
        val noisyTrace = "wertyuikjnjhgfderfgyuikl"

        val suggestions = LocalDictionary.suggestGlide(prefs, noisyTrace, 2)
        assertEquals("wonderful", suggestions.firstOrNull())
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
        return FakeSharedPreferences().also { prefs ->
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

}
