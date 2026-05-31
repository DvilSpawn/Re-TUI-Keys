package com.dvil.retui.keyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object KeyboardProfileBackup {
    private const val EXPORT_VERSION = 3

    private val profileKeys = listOf(
        KeyboardPrefs.KEY_BACKGROUND_IMAGE_OPACITY,
        KeyboardPrefs.KEY_BACKGROUND_IMAGE_URI,
        KeyboardPrefs.KEY_BOTTOM_MARGIN_DP,
        KeyboardPrefs.KEY_CORNER_RADIUS_DP,
        KeyboardPrefs.KEY_HEIGHT_PERCENT,
        KeyboardPrefs.KEY_HORIZONTAL_MARGIN_DP,
        KeyboardPrefs.KEY_KEY_GAP_DP,
        KeyboardPrefs.KEY_LANDSCAPE_HEIGHT_PERCENT,
        KeyboardPrefs.KEY_LEARN_LOCAL_WORDS,
        KeyboardPrefs.KEY_LEGACY_OUTER_MARGIN_DP,
        KeyboardPrefs.KEY_LOCAL_SUGGESTIONS,
        KeyboardPrefs.KEY_PORTRAIT_HEIGHT_PERCENT,
        KeyboardPrefs.KEY_QUICK_PERIOD,
        KeyboardPrefs.KEY_SHOW_ARROW_ROW,
        KeyboardPrefs.KEY_SHOW_NUMBER_ROW,
        KeyboardPrefs.KEY_SOUND_ON_KEYPRESS,
        KeyboardPrefs.KEY_SPLIT_KEYBOARD,
        KeyboardPrefs.KEY_STROKE_WIDTH_DP,
        KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN,
        KeyboardPrefs.KEY_VIBRATE_ON_KEYPRESS,
        "theme.bg",
        "theme.text",
        "theme.border",
        "theme.panelBg",
        "theme.headerBg",
        "theme.headerTabBorder",
        "theme.headerText",
        "theme.keyBg",
        "theme.keyText",
        "theme.outputBg",
        "theme.outputBorder",
        "theme.fontSizeSp",
        "theme.dashedBorders",
        "theme.dashLengthDp",
        "theme.dashGapDp",
        "theme.dashedStrokeWidthDp",
        "theme.moduleCornerRadiusDp",
        "theme.outputCornerRadiusDp",
        "theme.headerCornerRadiusDp",
        "theme.moduleBodyTextSizeSp",
        "theme.outputHeaderTextSizeSp",
        "theme.cyberdeckMode",
        "theme.crtFilter"
    )

    fun exportJson(prefs: SharedPreferences): String {
        return JSONObject()
            .put("version", EXPORT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("preferences", exportPreferences(prefs))
            .put("dictionary", JSONObject(LocalDictionary.exportJson(prefs)))
            .toString(2)
    }

    fun importJson(prefs: SharedPreferences, rawJson: String): RestoreResult {
        val trimmed = rawJson.trim()
        val root = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
        if (root == null || (!root.has("preferences") && !root.has("dictionary"))) {
            val result = LocalDictionary.importJson(prefs, rawJson)
            return RestoreResult(wordCount = result.wordCount, preferenceCount = 0)
        }

        val editor = prefs.edit()
        profileKeys.forEach { editor.remove(it) }
        val rawPreferences = root.optJSONObject("preferences")
        val preferenceCount = importPreferences(rawPreferences, editor)
        if (rawPreferences != null && !rawPreferences.has(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN) && containsLegacyThemeOverride(rawPreferences)) {
            editor.putBoolean(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN, true)
        }
        editor.apply()

        val dictionary = root.opt("dictionary")
        val dictionaryResult = when (dictionary) {
            is JSONObject -> LocalDictionary.importJson(prefs, dictionary.toString())
            is JSONArray -> LocalDictionary.importJson(prefs, dictionary.toString())
            is String -> LocalDictionary.importJson(prefs, dictionary)
            else -> ImportResult(LocalDictionary.userWords(prefs).size)
        }
        KeyboardPrefs.migrateLayout(prefs)
        return RestoreResult(
            wordCount = dictionaryResult.wordCount,
            preferenceCount = preferenceCount
        )
    }

    private fun exportPreferences(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        val all = prefs.all
        profileKeys.forEach { key ->
            if (!all.containsKey(key)) return@forEach
            val value = all[key] ?: return@forEach
            val item = JSONObject()
            when (value) {
                is Boolean -> item.put("type", "boolean").put("value", value)
                is Int -> item.put("type", "int").put("value", value)
                is Long -> item.put("type", "long").put("value", value)
                is Float -> item.put("type", "float").put("value", value.toDouble())
                is String -> item.put("type", "string").put("value", value)
                else -> return@forEach
            }
            out.put(key, item)
        }
        return out
    }

    private fun importPreferences(raw: JSONObject?, editor: SharedPreferences.Editor): Int {
        raw ?: return 0
        var count = 0
        raw.keys().forEach { key ->
            if (!profileKeys.contains(key)) return@forEach
            val item = raw.optJSONObject(key) ?: return@forEach
            val type = item.optString("type", "")
            if (!item.has("value")) return@forEach
            when (type) {
                "boolean" -> editor.putBoolean(key, item.optBoolean("value"))
                "int" -> editor.putInt(key, item.optInt("value"))
                "long" -> editor.putLong(key, item.optLong("value"))
                "float" -> editor.putFloat(key, item.optDouble("value").toFloat())
                "string" -> editor.putString(key, item.optString("value"))
                else -> return@forEach
            }
            count++
        }
        return count
    }

    private fun containsLegacyThemeOverride(raw: JSONObject): Boolean {
        raw.keys().forEach { key ->
            if (key.startsWith("theme.") && key != KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN) return true
        }
        return false
    }
}

data class RestoreResult(
    val wordCount: Int,
    val preferenceCount: Int
)
