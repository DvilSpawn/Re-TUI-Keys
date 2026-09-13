package com.dvil.retui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TypedWordCasingTest {
    private val lithuanian = Locale.forLanguageTag("lt-LT")
    private val russian = Locale.forLanguageTag("ru-RU")
    private val turkish = Locale.forLanguageTag("tr-TR")

    @Test
    fun capitalizedPrefixSurvivesAcceptingASuggestion() {
        assertEquals("Labai", TypedWordCasing.apply("Lab", "labai", lithuanian))
        assertEquals("Человек", TypedWordCasing.apply("Чело", "человек", russian))
    }

    @Test
    fun lowercasePrefixLeavesTheSuggestionAlone() {
        assertEquals("labai", TypedWordCasing.apply("lab", "labai", lithuanian))
    }

    @Test
    fun allCapsPrefixUppercasesTheWholeWord() {
        assertEquals("LABAI", TypedWordCasing.apply("LAB", "labai", lithuanian))
        assertEquals("ČIA", TypedWordCasing.apply("ČI", "čia", lithuanian))
    }

    @Test
    fun singleCapitalIsTitleCaseNotAllCaps() {
        assertEquals("Labai", TypedWordCasing.apply("L", "labai", lithuanian))
    }

    @Test
    fun mixedCasePrefixIsKeptAsTyped() {
        assertEquals("McDonald", TypedWordCasing.apply("McD", "mcdonald", Locale.ROOT))
    }

    @Test
    fun packLocaleDecidesHowThePrefixMatches() {
        assertEquals("İstanbul", TypedWordCasing.apply("İst", "istanbul", turkish))
        assertEquals("Iğdır", TypedWordCasing.apply("Iğ", "ığdır", turkish))
    }

    @Test
    fun correctionThatDoesNotShareThePrefixStillTakesTheCapital() {
        assertEquals("Labas", TypedWordCasing.apply("Lbas", "labas", lithuanian))
    }

    @Test
    fun emptyOrCaselessInputChangesNothing() {
        assertEquals("labai", TypedWordCasing.apply("", "labai", lithuanian))
        assertEquals("سلام", TypedWordCasing.apply("سل", "سلام", Locale.forLanguageTag("fa-IR")))
    }
}
