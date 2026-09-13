package com.dvil.retui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LanguagePackTest {
    @Test
    fun persianPackLoadsAndCompletesWords() {
        val pack = LanguagePackArchive.parse(persianPackFile().readBytes())
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
        val pack = LanguagePackArchive.parse(persianPackFile().readBytes())
        val prefs = FakeSharedPreferences()

        assertTrue(LanguagePackDictionary.learnTypedWord(pack, prefs, "رتویی", force = true))
        assertTrue(LanguagePackDictionary.suggest(pack, prefs, "رتو", 5).contains("رتویی"))
        assertTrue(LocalDictionary.suggest(prefs, "ret", 5).none { it == "رتویی" })
    }

    @Test
    fun caselessScriptKeepsShiftHidden() {
        val pack = LanguagePackArchive.parse(persianPackFile().readBytes())

        assertFalse(pack.bicameral)
        assertEquals("ض", pack.upperKey("ض"))
    }

    @Test
    fun latinPackEnablesShift() {
        val pack = parse(
            manifest(
                id = "lt-LT",
                languageTag = "lt-LT",
                name = "Lithuanian",
                nativeName = "Lietuvių",
                switchLabel = "LT",
                rows = listOf(
                    listOf("ą", "ž", "e", "r", "t", "y", "u", "i", "o", "p"),
                    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                    listOf("z", "x", "c", "v", "b", "n", "m", "š", "č", "ų")
                )
            ),
            words = latinWords
        )

        assertTrue(pack.bicameral)
        assertEquals("Ų", pack.upperKey("ų"))
        assertEquals("Č", pack.upperKey("č"))
    }

    @Test
    fun cyrillicPackEnablesShiftAndMatchesWordsCaseInsensitively() {
        val pack = parse(
            manifest(
                id = "ru-RU",
                languageTag = "ru-RU",
                name = "Russian",
                nativeName = "Русский",
                switchLabel = "РУ",
                rows = listOf(
                    listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х", "ъ"),
                    listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"),
                    listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю")
                )
            ),
            words = cyrillicWords
        )
        val prefs = FakeSharedPreferences()

        assertTrue(pack.bicameral)
        assertEquals("Й", pack.upperKey("й"))
        assertEquals("привет", pack.normalizeWord("Привет"))
        assertTrue(LanguagePackDictionary.containsKnownWord(pack, prefs, "Привет"))
        assertTrue(LanguagePackDictionary.suggest(pack, prefs, "Прив", 5).contains("приветствие"))
    }

    @Test
    fun packLocaleDrivesTheShiftedKey() {
        val pack = parse(
            manifest(
                id = "tr-TR",
                languageTag = "tr-TR",
                name = "Turkish",
                nativeName = "Türkçe",
                switchLabel = "TR",
                rows = listOf(
                    listOf("f", "g", "ğ", "ı", "o", "d", "r", "n", "h", "p"),
                    listOf("u", "i", "e", "a", "ü", "t", "k", "m", "l"),
                    listOf("j", "ö", "v", "c", "ç", "z", "s", "b")
                )
            ),
            words = latinWords
        )

        assertEquals("İ", pack.upperKey("i"))
        assertEquals("I", pack.upperKey("ı"))
    }

    @Test
    fun casingNoneKeepsShiftHiddenForLatinRows() {
        val pack = parse(
            manifest(
                id = "lt-none",
                languageTag = "lt-LT",
                name = "Lithuanian caseless",
                nativeName = "Lietuvių",
                switchLabel = "LT",
                rows = listOf(
                    listOf("ą", "ž", "e", "r", "t", "y", "u", "i", "o", "p"),
                    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                    listOf("z", "x", "c", "v", "b", "n", "m", "š", "č", "ų")
                ),
                casing = "none"
            ),
            words = latinWords
        )

        assertFalse(pack.bicameral)
        assertEquals("ų", pack.upperKey("ų"))
    }

    @Test
    fun narrowBottomRowKeepsRequestedSpecialKeyWidths() {
        val requested = listOf(1.35f, 1.25f)

        assertEquals(requested, LanguagePackLayout.specialWeights(letterCount = 9, requested = requested))
    }

    @Test
    fun wideBottomRowShrinksSpecialKeysInsteadOfLetters() {
        val requested = listOf(1.35f, 1.15f, 1.25f)
        val weights = LanguagePackLayout.specialWeights(letterCount = 12, requested = requested)

        assertEquals(requested.size, weights.size)
        assertTrue(weights.sum() < requested.sum())
        assertEquals(requested.size * LanguagePackLayout.MIN_SPECIAL_UNITS, weights.sum(), 0.001f)
        assertTrue(weights[0] > weights[2] && weights[2] > weights[1])
    }

    @Test
    fun emptySpecialKeyListStaysEmpty() {
        assertTrue(LanguagePackLayout.specialWeights(letterCount = 9, requested = emptyList()).isEmpty())
    }

    @Test
    fun staggeredIsTheDefaultRowStyle() {
        assertFalse(LanguagePackArchive.parse(persianPackFile().readBytes()).gridRows)
    }

    @Test
    fun shippedLithuanianPackIsATwelveColumnGrid() {
        val pack = LanguagePackArchive.parse(distPackFile("lithuanian-lt-LT-v1").readBytes())
        val prefs = FakeSharedPreferences()

        assertEquals("lt-LT", pack.id)
        assertTrue(pack.gridRows)
        assertTrue(pack.bicameral)
        assertEquals(listOf(12, 12, 11), pack.rows.map { it.size })
        assertEquals(12, pack.gridColumns)
        // The full Lithuanian alphabet is reachable without a symbol layer.
        assertTrue(pack.rows.flatten().containsAll("aąbcčdeęėfghiįyjklmnoprsštuųūvzž".map(Char::toString)))
        assertEquals("Ž", pack.upperKey("ž"))
        assertTrue(LanguagePackDictionary.suggest(pack, prefs, "lab", 4).contains("labai"))
    }

    @Test
    fun shippedRussianPackIsATwelveColumnGrid() {
        val pack = LanguagePackArchive.parse(distPackFile("russian-ru-RU-v1").readBytes())
        val prefs = FakeSharedPreferences()

        assertEquals("ru-RU", pack.id)
        assertTrue(pack.gridRows)
        assertTrue(pack.bicameral)
        assertEquals(listOf(12, 12, 11), pack.rows.map { it.size })
        assertTrue(pack.rows.flatten().containsAll("абвгдеёжзийклмнопрстуфхцчшщъыьэюя".map(Char::toString)))
        assertEquals("Ж", pack.upperKey("ж"))
        assertEquals("привет", pack.normalizeWord("Привет"))
        assertTrue(LanguagePackDictionary.suggest(pack, prefs, "чело", 4).contains("человек"))
    }

    @Test
    fun gridPackKeepsTwelveColumnsWithBackspaceInTheLastRow() {
        val pack = parse(manifest(rows = ortholinearRows, rowStyle = "grid"), words = latinWords)

        assertTrue(pack.gridRows)
        assertEquals(12, pack.gridColumns)
        assertEquals(12, pack.rows[0].size)
        assertEquals(12, pack.rows[1].size)
        assertEquals(11, pack.rows[2].size)
        assertTrue(pack.bicameral)
    }

    @Test
    fun gridPackRejectsRowsThatWouldNotLineUp() {
        val skewed = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "å", "ä"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ö", "ø"),
            listOf("z", "x", "c", "v", "b", "n", "m", "æ", "ß", "þ", "ð")
        )
        val error = runCatching { parse(manifest(rows = skewed, rowStyle = "grid"), words = latinWords) }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Grid rows"))
    }

    @Test
    fun unknownRowStyleIsRejected() {
        val error = runCatching { parse(manifest(rows = ortholinearRows, rowStyle = "ortho"), words = latinWords) }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Invalid row style", error?.message)
    }

    @Test
    fun splitHalvesFollowTheWidestRow() {
        // Twelve-column grid: rows of 12, 12 and 12 (backspace plus eleven letters).
        assertEquals(6, LanguagePackLayout.halfColumns(listOf(12, 12, 12, 10)))
        assertEquals(6, LanguagePackLayout.splitIndex(columnCount = 12, halfColumns = 6))
        // The digit row is narrower and gets padded rather than stretched.
        assertEquals(5, LanguagePackLayout.splitIndex(columnCount = 10, halfColumns = 6))
    }

    @Test
    fun splitHalvesNeverOverflowTheirCluster() {
        val half = LanguagePackLayout.halfColumns(listOf(9, 11, 12))

        assertEquals(6, half)
        assertEquals(6, LanguagePackLayout.splitIndex(columnCount = 14, halfColumns = half))
    }

    @Test
    fun splitHalvesSurviveAnEmptyRowList() {
        assertEquals(1, LanguagePackLayout.halfColumns(listOf(0, 0)))
    }

    @Test
    fun repeatedReadsReuseTheParsedPack() {
        LanguagePackManager.clearCache()
        val file = File.createTempFile("retui-cache", ".retui-lang")
        file.deleteOnExit()
        file.writeBytes(distPackFile("lithuanian-lt-LT-v1").readBytes())

        val first = LanguagePackManager.parseFile(file)
        val second = LanguagePackManager.parseFile(file)

        assertNotNull(first)
        // Same instance, so the keyboard does not re-inflate 50,000 words on every onStartInput,
        // and the lazily built completion index survives.
        assertSame(first, second)
    }

    @Test
    fun rewritingTheFileInvalidatesTheCachedPack() {
        LanguagePackManager.clearCache()
        val file = File.createTempFile("retui-cache", ".retui-lang")
        file.deleteOnExit()
        file.writeBytes(distPackFile("lithuanian-lt-LT-v1").readBytes())
        val lithuanian = LanguagePackManager.parseFile(file)

        file.writeBytes(distPackFile("russian-ru-RU-v1").readBytes())
        val russian = LanguagePackManager.parseFile(file)

        assertEquals("lt-LT", lithuanian?.id)
        assertEquals("ru-RU", russian?.id)
    }

    @Test
    fun unreadableFileIsNotCached() {
        LanguagePackManager.clearCache()
        val file = File.createTempFile("retui-broken", ".retui-lang")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertNull(LanguagePackManager.parseFile(file))

        file.writeBytes(distPackFile("russian-ru-RU-v1").readBytes())
        assertEquals("ru-RU", LanguagePackManager.parseFile(file)?.id)
    }

    private fun persianPackFile(): File = distPackFile("persian-fa-IR-v1")

    private fun distPackFile(name: String): File = listOf(
        File("language-packs/dist/$name.retui-lang"),
        File("../language-packs/dist/$name.retui-lang")
    ).first(File::isFile)

    private fun parse(manifest: String, words: List<String>): LanguagePack {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("words.tsv"))
            zip.write(words.mapIndexed { index, word -> "$word\t${words.size - index}" }
                .joinToString("\n")
                .toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return LanguagePackArchive.parse(out.toByteArray())
    }

    private fun manifest(
        rows: List<List<String>>,
        id: String = "lt-LT",
        languageTag: String = "lt-LT",
        name: String = "Lithuanian",
        nativeName: String = "Lietuvių",
        switchLabel: String = "LT",
        casing: String = "lower",
        rowStyle: String = "staggered"
    ): String {
        val renderedRows = rows.joinToString(",") { row ->
            row.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
        }
        return """
            {
              "schema": 1,
              "id": "$id",
              "languageTag": "$languageTag",
              "name": "$name",
              "nativeName": "$nativeName",
              "switchLabel": "$switchLabel",
              "version": 1,
              "direction": "ltr",
              "casing": "$casing",
              "rowStyle": "$rowStyle",
              "rows": [$renderedRows]
            }
        """.trimIndent()
    }

    private companion object {
        /** reviung41 / corne shape: two twelve-key letter rows, then backspace plus eleven letters. */
        val ortholinearRows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "ą", "ų"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ė", "į", "š"),
            listOf("z", "x", "c", "v", "b", "n", "m", "ž", "č", "ū", "ę")
        )
        val latinWords = listOf(
            "labas", "rytas", "diena", "naktis", "namas", "medis",
            "vanduo", "ugnis", "žmogus", "kalba", "šalis", "čia"
        )
        val cyrillicWords = listOf(
            "привет", "приветствие", "день", "ночь", "дом", "дерево",
            "вода", "огонь", "человек", "язык", "страна", "здесь"
        )
    }
}
