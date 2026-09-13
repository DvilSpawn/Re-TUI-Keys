package com.dvil.retui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalStoresTest {
    @Test
    fun clipboardItemsAreRecentDedupedAndExpired() {
        val prefs = FakeSharedPreferences()
        val day = 24L * 60L * 60L * 1000L

        LocalClipboardStore.add(prefs, "old", retentionDays = 2, now = 0L)
        LocalClipboardStore.add(prefs, "new", retentionDays = 2, now = day)
        LocalClipboardStore.add(prefs, "old", retentionDays = 2, now = day * 2)

        assertEquals(listOf("old", "new"), LocalClipboardStore.items(prefs, 2, now = day * 2).map { it.text })
        assertEquals(listOf("old"), LocalClipboardStore.items(prefs, 1, now = day * 3).map { it.text })
    }

    @Test
    fun pinnedClipboardItemsIgnoreExpiryAndKeepAdditionOrder() {
        val prefs = FakeSharedPreferences()
        val day = 24L * 60L * 60L * 1000L
        LocalClipboardStore.add(prefs, "first", 1, 1L)
        LocalClipboardStore.add(prefs, "second", 1, 2L)
        LocalClipboardStore.setPinned(prefs, "first", true)
        LocalClipboardStore.setPinned(prefs, "second", true)

        assertEquals(listOf("second", "first"), LocalClipboardStore.items(prefs, 1, day * 3).map { it.text })
    }

    @Test
    fun emojiRecentsMoveUsedEmojiToFront() {
        val prefs = FakeSharedPreferences()

        EmojiRecentsStore.record(prefs, "😀")
        EmojiRecentsStore.record(prefs, "👍")
        EmojiRecentsStore.record(prefs, "😀")

        assertEquals(listOf("😀", "👍"), EmojiRecentsStore.items(prefs))
    }

}
