package com.dvil.retui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherShortcutContractTest {
    @Test
    fun acceptsTwoOpaqueActionsAndPersistsLastValidSnapshot() {
        val prefs = FakeSharedPreferences()
        val raw = payload(
            """[
                {"id":"open-notes","label":"Notes","icon_uri":"content://com.dvil.tui_renewed.FILE_PROVIDER/icons/notes.png"},
                {"id":"call-home","label":"Call home","icon_uri":"content://com.dvil.tui_renewed.FILE_PROVIDER/icons/call.png"}
            ]"""
        )

        val state = LauncherShortcutContract.accept(prefs, raw)!!

        assertEquals(listOf("open-notes", "call-home"), state.forKey("O").map { it.id })
        assertEquals(state, LauncherShortcutContract.read(prefs))
        assertNull(LauncherShortcutContract.accept(prefs, payload("[{}, {}, {}]")))
        assertEquals(state, LauncherShortcutContract.read(prefs))
    }

    @Test
    fun rejectsExecutableOrUntrustedPayloads() {
        assertNull(LauncherShortcutContract.parse(payload("[]", token = "short")))
        assertNull(
            LauncherShortcutContract.parse(
                payload("""[{"id":"x","label":"X","icon_uri":"file:///data/icon.png"}]""")
            )
        )
        assertNull(
            LauncherShortcutContract.parse(
                payload("""[{"id":"open https://example.com","label":"X","icon_uri":"content://com.dvil.tui_renewed.FILE_PROVIDER/icons/x.png"}]""")
            )
        )
        assertNull(
            LauncherShortcutContract.parse(
                """{"version":1,"token":"1234567890abcdef","keys":{"open https://example.com":[]}}"""
            )
        )
    }

    private fun payload(actions: String, token: String = "1234567890abcdef") =
        """{"version":1,"token":"$token","keys":{"o":$actions}}"""
}
