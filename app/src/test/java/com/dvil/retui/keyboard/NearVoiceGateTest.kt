package com.dvil.retui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearVoiceGateTest {
    private fun tone(amplitude: Float) = FloatArray(512) { if (it % 2 == 0) amplitude else -amplitude }

    private fun NearVoiceGate.phrase(amplitude: Float): Boolean {
        repeat(20) { addSpeech(tone(amplitude)) }
        return finishPhrase()
    }

    @Test
    fun firstPhraseSetsTheReference() {
        val gate = NearVoiceGate()
        assertTrue(gate.phrase(0.2f))
        assertEquals(-14f, gate.referenceDb!!, 0.1f)
    }

    @Test
    fun muchQuieterPhraseIsDroppedAndDoesNotMoveTheReference() {
        val gate = NearVoiceGate()
        gate.phrase(0.2f)
        val before = gate.referenceDb
        assertFalse(gate.phrase(0.02f))
        assertEquals(before, gate.referenceDb)
    }

    @Test
    fun slightlyQuieterPhraseIsKept() {
        val gate = NearVoiceGate()
        gate.phrase(0.2f)
        assertTrue(gate.phrase(0.1f))
    }

    @Test
    fun chatterFirstThenUserRaisesTheReferenceSoChatterIsDroppedAfterwards() {
        val gate = NearVoiceGate()
        assertTrue(gate.phrase(0.02f))
        assertTrue(gate.phrase(0.3f))
        assertTrue(gate.phrase(0.3f))
        assertFalse(gate.phrase(0.02f))
    }

    @Test
    fun partialCheckMatchesTheStoredReference() {
        val gate = NearVoiceGate(-14f)
        gate.addSpeech(tone(0.02f))
        assertFalse(gate.passesSoFar())
        gate.finishPhrase()
        gate.addSpeech(tone(0.2f))
        assertTrue(gate.passesSoFar())
    }

    @Test
    fun aSessionWhereEverythingWasDroppedRelaxesTheReference() {
        val gate = NearVoiceGate(-14f)
        assertFalse(gate.phrase(0.02f))
        gate.finishSession()
        assertEquals(-20f, gate.referenceDb!!, 0.01f)
    }

    @Test
    fun aSessionWithAcceptedSpeechDoesNotRelax() {
        val gate = NearVoiceGate(-14f)
        gate.phrase(0.2f)
        gate.phrase(0.02f)
        gate.finishSession()
        assertEquals(-14f, gate.referenceDb!!, 0.2f)
    }
}
