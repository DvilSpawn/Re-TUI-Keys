package com.dvil.retui.keyboard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalStoresTest {
    @Test
    fun clipboardItemsAreRecentDedupedAndExpired() {
        val prefs = FakePrefs()
        val day = 24L * 60L * 60L * 1000L

        LocalClipboardStore.add(prefs, "old", retentionDays = 2, now = 0L)
        LocalClipboardStore.add(prefs, "new", retentionDays = 2, now = day)
        LocalClipboardStore.add(prefs, "old", retentionDays = 2, now = day * 2)

        assertEquals(listOf("old", "new"), LocalClipboardStore.items(prefs, 2, now = day * 2).map { it.text })
        assertEquals(listOf("old"), LocalClipboardStore.items(prefs, 1, now = day * 3).map { it.text })
    }

    @Test
    fun emojiRecentsMoveUsedEmojiToFront() {
        val prefs = FakePrefs()

        EmojiRecentsStore.record(prefs, "😀")
        EmojiRecentsStore.record(prefs, "👍")
        EmojiRecentsStore.record(prefs, "😀")

        assertEquals(listOf("😀", "👍"), EmojiRecentsStore.items(prefs))
    }

    private class FakePrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(private val values: MutableMap<String, Any>) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null && value != null) values[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { if (key != null) values[key] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) values.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { values.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
