package com.dvil.retui.keyboard

import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.zip.GZIPInputStream

class FutoSwipeEvalTest {
    @Test
    fun evaluateFutoSwipeSample() {
        val explicitPath = System.getProperty("futoSwipeEvalFile") ?: System.getenv("FUTO_SWIPE_EVAL_FILE")
        val file = explicitPath?.let(::File)?.takeIf { it.exists() } ?: return
        installLatinImeForJvm()

        val prefs = FakeSharedPreferences()
        val centers = futoQwertyCenters()
        var rows = 0
        var knownRows = 0
        var top1 = 0
        var top5 = 0
        var knownTop1 = 0
        var knownTop5 = 0
        val rankCounts = linkedMapOf("first" to 0, "top5" to 0, "missing" to 0)
        val rerankLengthDelta = linkedMapOf<Int, Int>()
        val winnerCounts = linkedMapOf<String, Int>()
        val rerankWinnerCounts = linkedMapOf<String, Int>()
        val rerankPairs = linkedMapOf<String, Int>()
        val misses = mutableListOf<String>()

        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val row = JSONObject(line)
            if (row.optString("orientation") != "portrait-primary") return@forEachLine
            val word = LocalDictionary.normalizeWord(row.optString("word")) ?: return@forEachLine
            val data = row.getJSONArray("data")
            if (data.length() < 2) return@forEachLine

            val points = List(data.length()) { index ->
                val point = data.getJSONObject(index)
                GlidePoint(point.getDouble("x").toFloat(), point.getDouble("y").toFloat())
            }
            val suggestions = LocalDictionary.suggestGlideGeometry(
                prefs = prefs,
                points = points,
                keyCenters = centers,
                rawTrace = traceFor(points, centers),
                limit = 5,
                previousWords = previousWords(row)
            ).mapNotNull { LocalDictionary.normalizeWord(it) }

            val hit1 = suggestions.firstOrNull() == word
            val hit5 = word in suggestions
            val known = LocalDictionary.isBuiltInWord(word)
            rows++
            if (hit1) top1++
            if (hit5) top5++
            if (known) {
                knownRows++
                if (hit1) knownTop1++
                if (hit5) knownTop5++
            }
            when {
                hit1 -> rankCounts["first"] = rankCounts.getValue("first") + 1
                hit5 -> {
                    rankCounts["top5"] = rankCounts.getValue("top5") + 1
                    val winner = suggestions.firstOrNull()
                    if (winner != null) {
                        rerankLengthDelta[winner.length - word.length] = rerankLengthDelta.getOrDefault(winner.length - word.length, 0) + 1
                        rerankWinnerCounts[winner] = rerankWinnerCounts.getOrDefault(winner, 0) + 1
                        val pair = "$winner>$word"
                        rerankPairs[pair] = rerankPairs.getOrDefault(pair, 0) + 1
                    }
                }
                else -> rankCounts["missing"] = rankCounts.getValue("missing") + 1
            }
            suggestions.firstOrNull()?.let { winner ->
                if (winner != word) winnerCounts[winner] = winnerCounts.getOrDefault(winner, 0) + 1
            }
            if (!hit1 && misses.size < 25) {
                misses.add("${row.optString("word")} -> ${suggestions.take(5)} trace=${traceFor(points, centers)}")
            }
        }

        val report = buildString {
            appendLine("rows=$rows")
            appendLine("top1=$top1 ${pct(top1, rows)}")
            appendLine("top5=$top5 ${pct(top5, rows)}")
            appendLine("knownRows=$knownRows")
            appendLine("knownTop1=$knownTop1 ${pct(knownTop1, knownRows)}")
            appendLine("knownTop5=$knownTop5 ${pct(knownTop5, knownRows)}")
            appendLine("rankCounts=$rankCounts")
            appendLine("rerankLengthDelta=${rerankLengthDelta.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value}" }}")
            appendLine("rerankWinners=${topCounts(rerankWinnerCounts)}")
            appendLine("rerankPairs=${topCounts(rerankPairs)}")
            appendLine("missWinners=${topCounts(winnerCounts)}")
            appendLine("misses:")
            misses.forEach { appendLine(it) }
        }
        File("build/futo-swipe-eval-result.txt").writeText(report)
        println(report)
    }

    private fun installLatinImeForJvm() {
        val entriesByWord = linkedMapOf<String, Int>()
        val wordlist = listOf(
            File("app/src/main/assets/latinime/en_US_wordlist.combined.gz"),
            File("src/main/assets/latinime/en_US_wordlist.combined.gz")
        ).first { it.exists() }
        GZIPInputStream(wordlist.inputStream())
            .bufferedReader()
            .useLines { lines ->
                for (line in lines) {
                    if (entriesByWord.size >= 50_000) break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("word=")) continue
                    if (trimmed.contains("not_a_word=true") || trimmed.contains("possibly_offensive=true")) continue
                    val word = LocalDictionary.normalizeWord(combinedField(trimmed, "word") ?: continue) ?: continue
                    val frequency = combinedField(trimmed, "f")?.toIntOrNull() ?: continue
                    val weight = (frequency.coerceIn(1, 255) * 720).coerceIn(8_000, 180_000)
                    if ((entriesByWord[word] ?: 0) < weight) entriesByWord[word] = weight
                }
            }

        val entryClass = Class.forName("com.dvil.retui.keyboard.StaticWordEntry")
        val entryCtor = entryClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, String::class.java)
        entryCtor.isAccessible = true
        val entries = entriesByWord.map { (word, weight) -> entryCtor.newInstance(word, weight, searchKey(word)) }
        val index = LocalDictionary::class.java.getDeclaredMethod("buildStaticIndex", List::class.java).also {
            it.isAccessible = true
        }.invoke(LocalDictionary, entries)

        LocalDictionary::class.java.getDeclaredField("latinImeStaticSet").also {
            it.isAccessible = true
            it.set(null, entriesByWord.keys)
        }
        LocalDictionary::class.java.getDeclaredField("latinImeStaticIndex").also {
            it.isAccessible = true
            it.set(null, index)
        }
        LocalDictionary::class.java.getDeclaredField("latinImeLoaded").also {
            it.isAccessible = true
            it.setBoolean(null, true)
        }
    }

    private fun futoQwertyCenters(): Map<Char, GlidePoint> {
        val rows = listOf(
            "qwertyuiop" to (0.05f to (1f / 6f)),
            "asdfghjkl" to (0.10046729f to 0.5f),
            "zxcvbnm" to (0.20046729f to (5f / 6f))
        )
        return rows.flatMap { (letters, origin) ->
            letters.mapIndexed { index, char ->
                char to GlidePoint(origin.first + (index * 0.10f), origin.second)
            }
        }.toMap()
    }

    private fun traceFor(points: List<GlidePoint>, centers: Map<Char, GlidePoint>): String {
        val out = StringBuilder()
        points.forEach { point ->
            val char = centers.minByOrNull { (_, center) ->
                val dx = point.x - center.x
                val dy = point.y - center.y
                (dx * dx) + (dy * dy)
            }?.key ?: return@forEach
            if (out.isEmpty() || out.last() != char) out.append(char)
        }
        return out.toString()
    }

    private fun previousWords(row: JSONObject): List<String> {
        val index = row.optInt("word_idx", 0)
        return row.optString("sentence")
            .split(Regex("\\s+"))
            .take(index)
            .mapNotNull { LocalDictionary.normalizeWord(it.trim { char -> !char.isLetter() && char != '\'' }) }
            .takeLast(3)
    }

    private fun combinedField(line: String, key: String): String? {
        val prefix = "$key="
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val valueStart = start + prefix.length
        val valueEnd = line.indexOf(',', valueStart).let { if (it < 0) line.length else it }
        return line.substring(valueStart, valueEnd).takeIf { it.isNotBlank() }
    }

    private fun searchKey(word: String): String {
        val decomposed = Normalizer.normalize(word, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        decomposed.forEach { char ->
            when {
                char == '\'' -> Unit
                Character.getType(char) == Character.NON_SPACING_MARK.toInt() -> Unit
                char == 'æ' -> out.append("ae")
                char == 'œ' -> out.append("oe")
                char == 'ø' -> out.append('o')
                char == 'ß' -> out.append("ss")
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    private fun pct(count: Int, total: Int): String {
        if (total == 0) return "(0.0%)"
        return "(%.1f%%)".format(count * 100.0 / total)
    }

    private fun topCounts(values: Map<String, Int>): String {
        return values.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(12)
            .joinToString { "${it.key}:${it.value}" }
    }

}
