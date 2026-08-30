package com.dvil.retui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LanguagePackTest {
    @Test
    fun persianPackLoadsAndCompletesWords() {
        val packFile = listOf(
            File("language-packs/dist/persian-fa-IR-v1.retui-lang"),
            File("../language-packs/dist/persian-fa-IR-v1.retui-lang")
        ).first(File::isFile)
        val pack = LanguagePackArchive.parse(packFile.readBytes())
        val prefs = FakeSharedPreferences()

        assertEquals("fa-IR", pack.id)
        assertTrue(pack.rtl)
        assertTrue(pack.rows.flatten().containsAll(listOf("پ", "چ", "ژ", "گ")))
        assertTrue(pack.isWordChar('\u200C'))
        assertEquals("می‌شود", pack.normalizeWord("مي‌شود"))
        assertTrue(LanguagePackDictionary.suggest(pack, prefs, "سلا", 5).contains("سلام"))
        assertTrue(LanguagePackDictionary.suggest(pack, prefs, "برنا", 5).contains("برنامه"))
    }

    @Test
    fun persianLearnedWordsStayInThePersianDictionary() {
        val packFile = listOf(
            File("language-packs/dist/persian-fa-IR-v1.retui-lang"),
            File("../language-packs/dist/persian-fa-IR-v1.retui-lang")
        ).first(File::isFile)
        val pack = LanguagePackArchive.parse(packFile.readBytes())
        val prefs = FakeSharedPreferences()

        assertTrue(LanguagePackDictionary.learnTypedWord(pack, prefs, "رتویی", force = true))
        assertTrue(LanguagePackDictionary.suggest(pack, prefs, "رتو", 5).contains("رتویی"))
        assertTrue(LocalDictionary.suggest(prefs, "ret", 5).none { it == "رتویی" })
    }
}
