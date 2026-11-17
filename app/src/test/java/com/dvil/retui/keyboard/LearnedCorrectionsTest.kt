package com.dvil.retui.keyboard

import com.dvil.retui.keyboard.CorrectionTracker.Source
import com.dvil.retui.keyboard.CorrectionTracker.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedCorrectionsTest {
    private val endOfChat = CorrectionContext(previous = "you", next = "", app = "chat")
    private val nearTheShop = CorrectionContext(previous = "is", next = "the", app = "chat")

    private fun correct(prefs: FakeSharedPreferences, times: Int, context: CorrectionContext = endOfChat) {
        repeat(times) {
            LearnedCorrections.recordCorrection(prefs, "near", "nya", voice = true, typed = false, context = context)
        }
    }

    private fun suggested(prefs: FakeSharedPreferences, context: CorrectionContext) =
        LearnedCorrections.suggestions(prefs, "near", voice = true, context = context, limit = 3)

    @Test
    fun oneCorrectionIsNotEnoughToSuggest() {
        val prefs = FakeSharedPreferences()
        correct(prefs, 1)
        assertEquals(emptyList<String>(), suggested(prefs, endOfChat))
    }

    @Test
    fun repeatedCorrectionIsSuggestedInTheSameContext() {
        val prefs = FakeSharedPreferences()
        correct(prefs, 2)
        assertEquals(listOf("nya"), suggested(prefs, endOfChat))
    }

    @Test
    fun wordKeepsItsWeightWhereTheUserKeepsIt() {
        val prefs = FakeSharedPreferences()
        correct(prefs, 3)
        repeat(3) { LearnedCorrections.recordKept(prefs, "near", voice = true, context = nearTheShop) }

        assertEquals(emptyList<String>(), suggested(prefs, nearTheShop))
        assertEquals(listOf("nya"), suggested(prefs, endOfChat))
    }

    @Test
    fun changingTheWordBackCountsAgainstTheSwap() {
        val prefs = FakeSharedPreferences()
        correct(prefs, 2)
        LearnedCorrections.recordCorrection(prefs, "nya", "near", voice = true, typed = false, context = endOfChat)

        assertEquals(emptyList<String>(), suggested(prefs, endOfChat))
        assertEquals(emptyList<LearnedCorrection>(), LearnedCorrections.pairs(prefs))
    }

    @Test
    fun voiceSwapsAreNotOfferedForTypedWords() {
        val prefs = FakeSharedPreferences()
        correct(prefs, 3)
        assertEquals(
            emptyList<String>(),
            LearnedCorrections.suggestions(prefs, "near", voice = false, context = endOfChat, limit = 3)
        )
    }

    @Test
    fun typedWordsOnlyLearnCloseFixes() {
        val prefs = FakeSharedPreferences()
        val context = CorrectionContext("", "", null)
        assertTrue(LearnedCorrections.recordCorrection(prefs, "teh", "the", voice = false, typed = true, context = context))
        assertFalse(LearnedCorrections.recordCorrection(prefs, "good", "excellent", voice = false, typed = true, context = context))
        assertFalse(LearnedCorrections.recordCorrection(prefs, "near", "nea", voice = true, typed = false, context = context))
    }

    @Test
    fun staleSwapsFade() {
        val prefs = FakeSharedPreferences()
        val day = 24L * 60L * 60L * 1000L
        LearnedCorrections.recordCorrection(prefs, "near", "nya", true, false, endOfChat, now = 0L)
        LearnedCorrections.recordCorrection(prefs, "their", "there", true, false, endOfChat, now = 200 * day)

        assertEquals(listOf("their"), LearnedCorrections.pairs(prefs).map { it.from })
    }

    @Test
    fun settingsEditorRemovesDeletedLines() {
        val prefs = FakeSharedPreferences()
        correct(prefs, 2)
        LearnedCorrections.recordCorrection(prefs, "their", "there", true, false, endOfChat)
        assertEquals("near > nya\ntheir > there", LearnedCorrections.editableText(prefs))

        assertEquals(1, LearnedCorrections.keepOnly(prefs, "their > there"))
        assertEquals(emptyList<String>(), suggested(prefs, endOfChat))
    }

    // ---- Tracker ----

    private fun tracker() = CorrectionTracker().apply { noteInserted(listOf("miss", "you", "near"), Source.VOICE) }
    private val left = listOf("miss", "you")

    @Test
    fun retypingADictatedWordIsACorrection() {
        val tracker = tracker()
        assertNull(tracker.beginEdit(Spot(left, "near", null, false)))
        listOf("nea", "ne", "", "", "ny", "nya").forEach {
            assertNull(tracker.onSpot(Spot(left, it, null, false), ownEdit = true))
        }
        val correction = tracker.onSpot(Spot(listOf("you", "nya"), "", null, false), ownEdit = true)

        assertEquals(CorrectionTracker.Correction("near", "nya", Source.VOICE, "you", ""), correction)
        assertFalse(tracker.editing)
    }

    @Test
    fun wordsKeysDidNotInsertAreIgnored() {
        val tracker = tracker()
        tracker.beginEdit(Spot(left, "nearly", null, false))
        assertFalse(tracker.editing)
    }

    @Test
    fun deletingAWordWithoutReplacingItIsNotACorrection() {
        val tracker = tracker()
        tracker.beginEdit(Spot(left, "near", "today", false))
        tracker.onSpot(Spot(left, "", "today", false), ownEdit = true)
        assertNull(tracker.onSpot(Spot(left, "today", null, false), ownEdit = true))
        assertFalse(tracker.editing)
    }

    @Test
    fun retypedSameWordIsNotACorrection() {
        val tracker = tracker()
        tracker.beginEdit(Spot(left, "near", null, false))
        tracker.onSpot(Spot(left, "nea", null, false), ownEdit = true)
        tracker.onSpot(Spot(left, "near", null, false), ownEdit = true)
        assertNull(tracker.onSpot(Spot(listOf("you", "near"), "", null, false), ownEdit = true))
    }

    @Test
    fun deletingFurtherBackWaitsForTheReplacement() {
        val tracker = tracker()
        tracker.beginEdit(Spot(left, "near", null, false))
        tracker.onSpot(Spot(left, "", null, false), ownEdit = true)
        // The space before the word goes too, then comes back with the new word.
        assertNull(tracker.onSpot(Spot(listOf("miss"), "you", null, false), ownEdit = true))
        tracker.onSpot(Spot(left, "nya", null, false), ownEdit = true)

        assertEquals("nya", tracker.finish()?.to)
    }

    @Test
    fun appClearingTheFieldEndsTheEdit() {
        val tracker = CorrectionTracker().apply { noteInserted(listOf("near"), Source.VOICE) }
        tracker.beginEdit(Spot(emptyList(), "near", null, false))
        tracker.onSpot(Spot(emptyList(), "nya", null, false), ownEdit = true)
        val correction = tracker.onSpot(Spot(emptyList(), "", null, true), ownEdit = false)

        assertEquals("nya", correction?.to)
        assertEquals("", correction?.previous)
    }
}
