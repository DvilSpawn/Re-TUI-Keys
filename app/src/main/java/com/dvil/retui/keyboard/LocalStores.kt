package com.dvil.retui.keyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object LocalClipboardStore {
    private const val KEY_ITEMS = "clipboard.items.v1"
    private const val MAX_ITEMS = 30
    private const val MAX_TEXT_CHARS = 4000
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun items(prefs: SharedPreferences, retentionDays: Int, now: Long = System.currentTimeMillis()): List<LocalClipboardItem> {
        val maxAgeMs = retentionDays.coerceIn(1, 365).toLong() * DAY_MS
        val items = read(prefs)
            .filter { now - it.lastUsedAt <= maxAgeMs }
            .sortedByDescending { it.lastUsedAt }
            .take(MAX_ITEMS)
        write(prefs, items)
        return items
    }

    fun add(prefs: SharedPreferences, text: String, retentionDays: Int, now: Long = System.currentTimeMillis()) {
        val cleanText = text.take(MAX_TEXT_CHARS).takeIf { it.isNotBlank() } ?: return
        val current = items(prefs, retentionDays, now).filterNot { it.text == cleanText }
        write(prefs, listOf(LocalClipboardItem(cleanText, now, now)) + current)
    }

    fun markUsed(prefs: SharedPreferences, text: String, retentionDays: Int, now: Long = System.currentTimeMillis()) {
        val items = items(prefs, retentionDays, now)
        write(prefs, items.map { if (it.text == text) it.copy(lastUsedAt = now) else it })
    }

    fun remove(prefs: SharedPreferences, text: String) {
        write(prefs, read(prefs).filterNot { it.text == text })
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    private fun read(prefs: SharedPreferences): List<LocalClipboardItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { obj ->
                val text = obj?.optString("text").orEmpty()
                if (text.isBlank()) null else LocalClipboardItem(
                    text = text,
                    createdAt = obj?.optLong("createdAt") ?: 0L,
                    lastUsedAt = obj?.optLong("lastUsedAt") ?: 0L
                )
            }
    }

    private fun write(prefs: SharedPreferences, items: List<LocalClipboardItem>) {
        val array = JSONArray()
        items.take(MAX_ITEMS).forEach { item ->
            array.put(
                JSONObject()
                    .put("text", item.text)
                    .put("createdAt", item.createdAt)
                    .put("lastUsedAt", item.lastUsedAt)
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }
}

object EmojiRecentsStore {
    private const val KEY_RECENTS = "emoji.recents.v1"
    private const val MAX_RECENTS = 40

    fun items(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }.take(MAX_RECENTS)
    }

    fun record(prefs: SharedPreferences, emoji: String) {
        if (emoji.isBlank()) return
        val next = (listOf(emoji) + items(prefs).filterNot { it == emoji }).take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, next.joinToString("\n")).apply()
    }
}

data class LocalClipboardItem(
    val text: String,
    val createdAt: Long,
    val lastUsedAt: Long
)
