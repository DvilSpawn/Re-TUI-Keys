package com.dvil.retui.keyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** Where a word was when it was kept or changed. Null means unknown; empty means the text edge. */
internal data class CorrectionContext(val previous: String?, val next: String?, val app: String?) {
    fun features(): List<String> = listOfNotNull(
        previous?.let { "p:$it" },
        next?.let { "n:$it" },
        app?.takeIf { it.isNotBlank() }?.let { "a:$it" }
    )
}

internal data class LearnedCorrection(
    val from: String,
    val to: String,
    val voice: Boolean,
    val changed: Int,
    val lastAt: Long,
    val contexts: Map<String, Int>
)

/**
 * Words the user keeps changing after Keys put them in, with the context each change happened in.
 * A word that is left alone is counted too, so a swap is only offered where the user usually makes
 * it: "near" stays "near" in "near the shop" and becomes "nya" at the end of a message. Counting
 * only; nothing here changes text by itself.
 */
internal object LearnedCorrections {
    const val KEY = "typing.corrections.v1"
    const val MIN_REPEATS = 2
    private const val SUGGEST_FLOOR = -1.0
    private const val UNDO_WEIGHT = 2
    private const val MAX_PAIRS = 200
    private const val MAX_KEPT = 200
    private const val MAX_CONTEXTS = 16
    private const val MAX_COUNT = 1_000
    private const val STALE_MS = 120L * 24L * 60L * 60L * 1000L
    private const val TYPO_MAX_DISTANCE = 2

    private data class Kept(val word: String, val voice: Boolean, val count: Int, val contexts: Map<String, Int>)
    private data class State(val pairs: List<LearnedCorrection>, val kept: List<Kept>)

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cached = State(emptyList(), emptyList())

    fun pairs(prefs: SharedPreferences): List<LearnedCorrection> =
        read(prefs).pairs.sortedWith(compareBy<LearnedCorrection> { it.from }.thenBy { it.to })

    fun hasPairs(prefs: SharedPreferences, from: String, voice: Boolean): Boolean =
        read(prefs).pairs.any { it.from == from && it.voice == voice }

    fun hasPair(prefs: SharedPreferences, from: String, to: String, voice: Boolean): Boolean =
        read(prefs).pairs.any { it.from == from && it.to == to && it.voice == voice }

    /**
     * The user replaced [from] with [to]. Typed words only count when the two are close, so a
     * reworded sentence is not mistaken for a fixed typo. Changing a learned word back counts
     * against that swap instead of teaching the reverse.
     */
    fun recordCorrection(
        prefs: SharedPreferences,
        from: String,
        to: String,
        voice: Boolean,
        typed: Boolean,
        context: CorrectionContext,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (from.isBlank() || to.isBlank() || from == to) return false
        // What is left of a word while it is being deleted is not a replacement.
        if (from.startsWith(to)) return false
        val state = read(prefs)
        val undone = state.pairs.firstOrNull { it.from == to && it.to == from && it.voice == voice }
        if (undone != null) {
            val left = undone.changed - UNDO_WEIGHT
            val pairs = state.pairs.filterNot { it === undone } +
                listOfNotNull(undone.copy(changed = left).takeIf { left > 0 })
            write(prefs, State(pairs, bumpKept(state.kept, to, voice, context, 1)), now)
            return true
        }
        if (typed && !voice && editDistance(from, to) > TYPO_MAX_DISTANCE) return false
        val current = state.pairs.firstOrNull { it.from == from && it.to == to && it.voice == voice }
        val next = LearnedCorrection(
            from = from,
            to = to,
            voice = voice,
            changed = ((current?.changed ?: 0) + 1).coerceAtMost(MAX_COUNT),
            lastAt = now,
            contexts = bump(current?.contexts.orEmpty(), context, 1)
        )
        // The word was counted as kept when it went in; it turned out not to be.
        write(prefs, State(state.pairs.filterNot { it === current } + next, bumpKept(state.kept, from, voice, context, -1)), now)
        return true
    }

    /** Keys put [word] in and the user has not changed it. Only tracked for words with a learned swap. */
    fun recordKept(prefs: SharedPreferences, word: String, voice: Boolean, context: CorrectionContext) {
        val state = read(prefs)
        if (state.pairs.none { it.from == word && it.voice == voice }) return
        write(prefs, state.copy(kept = bumpKept(state.kept, word, voice, context, 1)), System.currentTimeMillis())
    }

    /** Learned replacements for [word] that fit [context], best first. */
    fun suggestions(
        prefs: SharedPreferences,
        word: String,
        voice: Boolean,
        context: CorrectionContext,
        limit: Int
    ): List<String> {
        val state = read(prefs)
        val kept = state.kept.firstOrNull { it.word == word && it.voice == voice }
        return state.pairs
            .filter { it.from == word && it.voice == voice && it.changed >= MIN_REPEATS }
            .map { it to score(it, kept, context) }
            .filter { it.second >= SUGGEST_FLOOR }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first.to }
    }

    /** One line per learned swap, for the settings editor. */
    fun editableText(prefs: SharedPreferences): String =
        pairs(prefs).joinToString("\n") { "${it.from} > ${it.to}" }

    /** Keeps only the swaps still listed in [rawText]; returns how many remain. */
    fun keepOnly(prefs: SharedPreferences, rawText: String): Int {
        val wanted = rawText.lines().mapNotNull { line ->
            val parts = line.split('>').map { it.trim().lowercase() }
            if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
        }.toSet()
        val state = read(prefs)
        val pairs = state.pairs.filter { (it.from to it.to) in wanted }
        val kept = state.kept.filter { entry -> pairs.any { it.from == entry.word && it.voice == entry.voice } }
        write(prefs, State(pairs, kept), System.currentTimeMillis())
        return pairs.size
    }

    /**
     * Log odds that the user wants the swap here: how often the word was changed against how often
     * it was kept, then the same comparison for each part of the context.
     */
    private fun score(pair: LearnedCorrection, kept: Kept?, context: CorrectionContext): Double {
        val changed = pair.changed.toDouble()
        val keptCount = (kept?.count ?: 0).toDouble()
        var odds = ln((changed + 0.5) / (keptCount + 0.5))
        context.features().forEach { feature ->
            val whenChanged = ((pair.contexts[feature] ?: 0) + 0.5) / (changed + 1.0)
            val whenKept = ((kept?.contexts?.get(feature) ?: 0) + 0.5) / (keptCount + 1.0)
            odds += ln(whenChanged / whenKept)
        }
        return odds
    }

    private fun bumpKept(kept: List<Kept>, word: String, voice: Boolean, context: CorrectionContext, by: Int): List<Kept> {
        val current = kept.firstOrNull { it.word == word && it.voice == voice }
        if (current == null && by < 0) return kept
        val count = ((current?.count ?: 0) + by).coerceIn(0, MAX_COUNT)
        val rest = kept.filterNot { it === current }
        if (count == 0) return rest
        return rest + Kept(word, voice, count, bump(current?.contexts.orEmpty(), context, by))
    }

    private fun bump(counts: Map<String, Int>, context: CorrectionContext, by: Int): Map<String, Int> {
        val out = counts.toMutableMap()
        context.features().forEach { feature ->
            val value = ((out[feature] ?: 0) + by).coerceAtMost(MAX_COUNT)
            if (value > 0) out[feature] = value else out.remove(feature)
        }
        return out.entries.sortedByDescending { it.value }.take(MAX_CONTEXTS).associate { it.key to it.value }
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) {
                val swap = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                row[j] = minOf(swap, previous[j] + 1, row[j - 1] + 1)
            }
            previous = row
        }
        return previous[b.length]
    }

    private fun read(prefs: SharedPreferences): State {
        val raw = prefs.getString(KEY, null) ?: return State(emptyList(), emptyList())
        if (raw == cachedRaw) return cached
        val parsed = try {
            val root = JSONObject(raw)
            val pairs = root.optJSONArray("pairs") ?: JSONArray()
            val kept = root.optJSONArray("kept") ?: JSONArray()
            State(
                pairs = List(pairs.length()) { index ->
                    val item = pairs.getJSONObject(index)
                    LearnedCorrection(
                        from = item.optString("from"),
                        to = item.optString("to"),
                        voice = item.optBoolean("voice"),
                        changed = item.optInt("changed", 1).coerceIn(1, MAX_COUNT),
                        lastAt = item.optLong("lastAt"),
                        contexts = readCounts(item.optJSONObject("contexts"))
                    )
                }.filter { it.from.isNotBlank() && it.to.isNotBlank() },
                kept = List(kept.length()) { index ->
                    val item = kept.getJSONObject(index)
                    Kept(
                        word = item.optString("word"),
                        voice = item.optBoolean("voice"),
                        count = item.optInt("count", 1).coerceIn(1, MAX_COUNT),
                        contexts = readCounts(item.optJSONObject("contexts"))
                    )
                }.filter { it.word.isNotBlank() }
            )
        } catch (_: Exception) {
            State(emptyList(), emptyList())
        }
        cachedRaw = raw
        cached = parsed
        return parsed
    }

    private fun readCounts(raw: JSONObject?): Map<String, Int> {
        raw ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        raw.keys().forEach { key -> raw.optInt(key).takeIf { it > 0 }?.let { out[key] = it } }
        return out
    }

    private fun write(prefs: SharedPreferences, state: State, now: Long) {
        // A swap nobody has made for months fades: its count halves, and it goes once it reaches zero.
        val pairs = state.pairs.mapNotNull { pair ->
            if (now - pair.lastAt < STALE_MS) pair
            else pair.copy(changed = pair.changed / 2, lastAt = now).takeIf { it.changed > 0 }
        }.sortedByDescending { it.lastAt }.take(MAX_PAIRS)
        val kept = state.kept
            .filter { entry -> pairs.any { it.from == entry.word && it.voice == entry.voice } }
            .take(MAX_KEPT)
        val root = JSONObject()
            .put("pairs", JSONArray().also { array ->
                pairs.forEach { pair ->
                    array.put(
                        JSONObject()
                            .put("from", pair.from)
                            .put("to", pair.to)
                            .put("voice", pair.voice)
                            .put("changed", pair.changed)
                            .put("lastAt", pair.lastAt)
                            .put("contexts", JSONObject(pair.contexts))
                    )
                }
            })
            .put("kept", JSONArray().also { array ->
                kept.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("word", entry.word)
                            .put("voice", entry.voice)
                            .put("count", entry.count)
                            .put("contexts", JSONObject(entry.contexts))
                    )
                }
            })
        prefs.edit().putString(KEY, root.toString()).apply()
    }
}

/**
 * Notices when the user replaces a word Keys put in. Keys reports the word under the cursor after
 * each change; an edit starts when the user begins deleting a word Keys inserted and ends when the
 * cursor leaves that word's place with something else there.
 */
internal class CorrectionTracker {
    enum class Source { VOICE, GLIDE, TYPED }

    /**
     * The cursor's surroundings: the whole [word] it touches (empty when between words), up to two
     * words to its [left], and the word to its [right] if there is one.
     */
    data class Spot(val left: List<String>, val word: String, val right: String?, val fieldEmpty: Boolean)

    data class Correction(val from: String, val to: String, val source: Source, val previous: String, val next: String)

    private class Edit(val original: String, val source: Source, val left: List<String>, val right: String?) {
        var current = original
        var strays = 0
    }

    private val recent = LinkedHashMap<String, Source>()
    private var edit: Edit? = null

    val editing: Boolean get() = edit != null
    val watching: Boolean get() = recent.isNotEmpty()

    fun noteInserted(words: List<String>, source: Source) {
        words.forEach { word ->
            recent.remove(word)
            recent[word] = source
        }
        while (recent.size > MAX_RECENT) recent.remove(recent.keys.first())
    }

    fun sourceOf(word: String): Source? = recent[word]

    /** Call before Keys deletes anything at [spot]. Returns a correction if this ends an earlier edit. */
    fun beginEdit(spot: Spot): Correction? {
        val open = edit
        if (open != null && open.left == spot.left) return null
        val finished = finish()
        val source = recent[spot.word] ?: return finished
        edit = Edit(spot.word, source, spot.left, spot.right)
        return finished
    }

    /** Keys knows the word now standing where the edit is, for example one just finished with a space. */
    fun noteWord(word: String) {
        edit?.current = word
    }

    /** Call after the cursor or text changed. [ownEdit] is false when the app or a tap moved things. */
    fun onSpot(spot: Spot, ownEdit: Boolean): Correction? {
        val open = edit ?: return null
        val samePlace = spot.left == open.left && (ownEdit || !spot.fieldEmpty)
        if (samePlace) {
            if (spot.word.isNotEmpty() && spot.word == open.right && spot.word != open.original) {
                // The word was deleted, not replaced: the cursor now touches what used to follow it.
                edit = null
                return null
            }
            open.current = spot.word
            open.strays = 0
            return null
        }
        // Still deleting or retyping around an empty gap: wait a little for the replacement.
        if (ownEdit && open.current.isEmpty() && ++open.strays <= MAX_STRAYS) return null
        return finish()
    }

    /** Ends the edit, for example when the field closes. */
    fun finish(): Correction? {
        val open = edit ?: return null
        edit = null
        if (open.current.isEmpty() || open.current == open.original) return null
        return Correction(open.original, open.current, open.source, open.left.lastOrNull().orEmpty(), open.right.orEmpty())
    }

    fun reset() {
        edit = null
        recent.clear()
    }

    private companion object {
        const val MAX_RECENT = 80
        const val MAX_STRAYS = 8
    }
}
