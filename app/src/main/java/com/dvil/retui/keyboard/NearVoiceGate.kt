package com.dvil.retui.keyboard

import android.content.SharedPreferences
import kotlin.math.log10
import kotlin.math.max

/** How on-phone voice typing treats people talking nearby. */
internal object VoiceFilter {
    const val KEY = "voice.backgroundFilter"
    const val KEY_NEAR_LEVEL_DB = "voice.nearLevelDb"
    const val OFF = "off"
    const val NEAR = "near"
    const val DEFAULT = NEAR

    fun mode(prefs: SharedPreferences): String = prefs.getString(KEY, DEFAULT) ?: DEFAULT

    fun gate(prefs: SharedPreferences): NearVoiceGate? {
        if (mode(prefs) == OFF) return null
        val stored = if (prefs.contains(KEY_NEAR_LEVEL_DB)) prefs.getFloat(KEY_NEAR_LEVEL_DB, 0f) else null
        return NearVoiceGate(stored)
    }

    fun save(prefs: SharedPreferences, gate: NearVoiceGate) {
        val level = gate.referenceDb ?: return
        prefs.edit().putFloat(KEY_NEAR_LEVEL_DB, level).apply()
    }
}

/**
 * Keeps the phrases spoken close to the microphone and drops much quieter ones. The person holding
 * the phone is far louder at the mic than people across the room, so each phrase is compared with
 * the level this user normally speaks at. That level is learned from accepted phrases and carried
 * between sessions; it drifts down when everything was rejected, so a quieter day cannot lock the
 * user out.
 */
internal class NearVoiceGate(initialReferenceDb: Float? = null) {
    var referenceDb: Float? = initialReferenceDb
        private set
    private var energy = 0.0
    private var samples = 0L
    private var accepted = 0
    private var rejected = 0

    /** Adds audio that the speech detector marked as speech in the current phrase. */
    fun addSpeech(window: FloatArray) {
        for (sample in window) energy += sample * sample
        samples += window.size
    }

    /** Loudness of the phrase so far in dBFS, or null before any speech. */
    fun phraseDb(): Float? = if (samples == 0L) null else levelDb(energy / samples)

    /** Whether the phrase so far is loud enough to be the near speaker. Does not learn. */
    fun passesSoFar(): Boolean {
        val level = phraseDb() ?: return false
        val reference = referenceDb ?: return true
        return level >= reference - MARGIN_DB
    }

    /** Ends the phrase: returns whether to keep it, and learns from it. */
    fun finishPhrase(): Boolean {
        val level = phraseDb()
        energy = 0.0
        samples = 0
        if (level == null) return false
        val reference = referenceDb
        if (reference == null) {
            referenceDb = level
            accepted++
            return true
        }
        if (level < reference - MARGIN_DB) {
            rejected++
            return false
        }
        // Rise quickly towards a louder phrase, settle slowly towards a quieter accepted one.
        val weight = if (level > reference) RISE else SETTLE
        referenceDb = reference + (level - reference) * weight
        accepted++
        return true
    }

    /** Call when dictation stops. If every phrase was rejected, assume the reference was too high. */
    fun finishSession() {
        val reference = referenceDb ?: return
        if (accepted == 0 && rejected > 0) referenceDb = reference - RELAX_DB
        accepted = 0
        rejected = 0
    }

    companion object {
        const val MARGIN_DB = 12f
        const val RELAX_DB = 6f
        private const val RISE = 0.5f
        private const val SETTLE = 0.1f

        fun levelDb(meanSquare: Double): Float = (10.0 * log10(max(meanSquare, 1e-10))).toFloat()
    }
}
