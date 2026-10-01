package com.dvil.retui.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class LocalDictionaryEvaluationTest {
    @Test
    fun offlineCorpusMeetsAccuracyAndLatencyBaseline() {
        // ponytail: small in-code corpus; move to a data file when the evaluation set outgrows this test.
        val centers = qwertyCenters()
        val typedPrefs = FakeSharedPreferences().also { prefs ->
            LocalDictionary.replaceUserWords(prefs, "zoll\nzall")
            repeat(2) {
                LocalDictionary.recordAcceptedWord(prefs, "zoll")
                LocalDictionary.recordAcceptedWord(prefs, "zall")
            }
        }
        val glidePrefs = FakeSharedPreferences().also {
            LocalDictionary.replaceUserWords(it, "built\nbuild\nbolt")
        }
        val repeatedPrefs = FakeSharedPreferences().also {
            LocalDictionary.replaceUserWords(it, "god")
        }
        val typedTaps = "zoll".map { centers.getValue(it) }
        val builtPath = "built".map { centers.getValue(it) }
        val repeatedPath = listOf(
            centers.getValue('g'),
            centers.getValue('o'),
            GlidePoint(centers.getValue('o').x + 28f, centers.getValue('o').y + 12f),
            GlidePoint(centers.getValue('o').x - 22f, centers.getValue('o').y + 10f),
            centers.getValue('o'),
            centers.getValue('d')
        )
        val cases = listOf(
            "typed tap correction" to { LocalDictionary.suggest(typedPrefs, "zill", 5, typedTaps, centers) },
            "glide geometry" to { LocalDictionary.suggestGlideGeometry(glidePrefs, builtPath, centers, "built", 5) },
            "repeated-letter glide" to { LocalDictionary.suggestGlideGeometry(repeatedPrefs, repeatedPath, centers, "god", 5) },
            "next word" to { LocalDictionary.suggestNextWords(FakeSharedPreferences(), "will", 5) },
            "email completion" to { LocalDictionary.suggestFieldCompletions("name@gm", FieldCompletionKind.EMAIL, 5) },
            "url completion" to { LocalDictionary.suggestFieldCompletions("example.c", FieldCompletionKind.URL, 5) }
        )
        val expected = listOf("zoll", "built", "good", "you", "name@gmail.com", "example.com")
        val results = cases.map { it.second() }
        val top1 = results.countIndexed { index, result -> result.firstOrNull() == expected[index] }
        val top5 = results.countIndexed { index, result -> expected[index] in result.take(5) }

        assertTrue("top-1 accuracy $top1/${cases.size}", top1 >= 5)
        assertTrue("top-5 accuracy $top5/${cases.size}", top5 == cases.size)

        val maxAverageMillis = cases.map { (_, run) ->
            measureNanoTime { repeat(30) { run() } } / 30_000_000.0
        }.maxOrNull() ?: 0.0
        println("offline corpus: top1=$top1/${cases.size}, top5=$top5/${cases.size}, maxAverageMs=$maxAverageMillis")
        assertTrue("latency baseline ${maxAverageMillis}ms", maxAverageMillis < 250.0)
    }

    private fun qwertyCenters(): Map<Char, GlidePoint> {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 100f)
        return rows.flatMap { (row, y) ->
            val xOffset = if (row == "asdfghjkl") 25f else if (row == "zxcvbnm") 75f else 0f
            row.mapIndexed { index, char -> char to GlidePoint(xOffset + (index * 50f), y) }
        }.toMap()
    }

    private fun <T> List<T>.countIndexed(predicate: (Int, T) -> Boolean): Int {
        return indices.count { predicate(it, this[it]) }
    }
}
