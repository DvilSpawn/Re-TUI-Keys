package com.dvil.retui.keyboard

import android.content.SharedPreferences

object KeyboardPrefs {
    const val PREFS_NAME = "retui_keyboard"

    const val KEY_BACKGROUND_IMAGE_OPACITY = "layout.backgroundImageOpacity"
    const val KEY_BACKGROUND_IMAGE_URI = "layout.backgroundImageUri"
    const val KEY_BOTTOM_MARGIN_DP = "layout.bottomMarginDp"
    const val KEY_CORNER_RADIUS_DP = "layout.cornerRadiusDp"
    const val KEY_HEIGHT_PERCENT = "layout.heightPercent"
    const val KEY_HORIZONTAL_MARGIN_DP = "layout.horizontalMarginDp"
    const val KEY_KEY_GAP_DP = "layout.keyGapDp"
    const val KEY_LEARN_LOCAL_WORDS = "typing.learnLocalWords"
    const val KEY_LEGACY_OUTER_MARGIN_DP = "layout.outerMarginDp"
    const val KEY_LOCAL_SUGGESTIONS = "typing.localSuggestions"
    const val KEY_QUICK_PERIOD = "layout.quickPeriod"
    const val KEY_SHOW_ARROW_ROW = "layout.showArrowRow"
    const val KEY_SHOW_NUMBER_ROW = "layout.showNumberRow"
    const val KEY_SOUND_ON_KEYPRESS = "layout.soundOnKeypress"
    const val KEY_STROKE_WIDTH_DP = "layout.strokeWidthDp"
    const val KEY_VIBRATE_ON_KEYPRESS = "layout.vibrateOnKeypress"

    const val DEFAULT_BACKGROUND_IMAGE_OPACITY = 55
    const val DEFAULT_BOTTOM_MARGIN_DP = 4
    const val DEFAULT_CORNER_RADIUS_DP = 0
    const val DEFAULT_HEIGHT_PERCENT = 100
    const val DEFAULT_HORIZONTAL_MARGIN_DP = 4
    const val DEFAULT_KEY_GAP_DP = 2
    const val DEFAULT_LEARN_LOCAL_WORDS = true
    const val DEFAULT_LOCAL_SUGGESTIONS = true
    const val DEFAULT_QUICK_PERIOD = true
    const val DEFAULT_SHOW_ARROW_ROW = false
    const val DEFAULT_SHOW_NUMBER_ROW = false
    const val DEFAULT_SOUND_ON_KEYPRESS = false
    const val DEFAULT_STROKE_WIDTH_DP = 1
    const val DEFAULT_VIBRATE_ON_KEYPRESS = false

    fun readLayout(prefs: SharedPreferences): KeyboardLayoutSettings {
        val legacyMargin = prefs.getInt(KEY_LEGACY_OUTER_MARGIN_DP, DEFAULT_HORIZONTAL_MARGIN_DP)
        return KeyboardLayoutSettings(
            backgroundImageOpacity = prefs.getInt(
                KEY_BACKGROUND_IMAGE_OPACITY,
                DEFAULT_BACKGROUND_IMAGE_OPACITY
            ).coerceIn(0, 100),
            backgroundImageUri = prefs.getString(KEY_BACKGROUND_IMAGE_URI, null)?.takeIf { it.isNotBlank() },
            bottomMarginDp = prefs.getInt(KEY_BOTTOM_MARGIN_DP, legacyMargin).coerceIn(0, 64),
            cornerRadiusDp = prefs.getInt(KEY_CORNER_RADIUS_DP, DEFAULT_CORNER_RADIUS_DP).coerceIn(0, 18),
            heightPercent = prefs.getInt(KEY_HEIGHT_PERCENT, DEFAULT_HEIGHT_PERCENT).coerceIn(80, 180),
            horizontalMarginDp = prefs.getInt(KEY_HORIZONTAL_MARGIN_DP, legacyMargin).coerceIn(0, 48),
            keyGapDp = prefs.getInt(KEY_KEY_GAP_DP, DEFAULT_KEY_GAP_DP).coerceIn(0, 8),
            learnLocalWords = prefs.getBoolean(KEY_LEARN_LOCAL_WORDS, DEFAULT_LEARN_LOCAL_WORDS),
            localSuggestions = prefs.getBoolean(KEY_LOCAL_SUGGESTIONS, DEFAULT_LOCAL_SUGGESTIONS),
            quickPeriod = prefs.getBoolean(KEY_QUICK_PERIOD, DEFAULT_QUICK_PERIOD),
            showArrowRow = prefs.getBoolean(KEY_SHOW_ARROW_ROW, DEFAULT_SHOW_ARROW_ROW),
            showNumberRow = prefs.getBoolean(KEY_SHOW_NUMBER_ROW, DEFAULT_SHOW_NUMBER_ROW),
            soundOnKeypress = prefs.getBoolean(KEY_SOUND_ON_KEYPRESS, DEFAULT_SOUND_ON_KEYPRESS),
            strokeWidthDp = prefs.getInt(KEY_STROKE_WIDTH_DP, DEFAULT_STROKE_WIDTH_DP).coerceIn(0, 5),
            vibrateOnKeypress = prefs.getBoolean(KEY_VIBRATE_ON_KEYPRESS, DEFAULT_VIBRATE_ON_KEYPRESS)
        )
    }

    fun resetLayout(editor: SharedPreferences.Editor) {
        editor
            .putInt(KEY_BACKGROUND_IMAGE_OPACITY, DEFAULT_BACKGROUND_IMAGE_OPACITY)
            .remove(KEY_BACKGROUND_IMAGE_URI)
            .putInt(KEY_BOTTOM_MARGIN_DP, DEFAULT_BOTTOM_MARGIN_DP)
            .putInt(KEY_CORNER_RADIUS_DP, DEFAULT_CORNER_RADIUS_DP)
            .putInt(KEY_HEIGHT_PERCENT, DEFAULT_HEIGHT_PERCENT)
            .putInt(KEY_HORIZONTAL_MARGIN_DP, DEFAULT_HORIZONTAL_MARGIN_DP)
            .putInt(KEY_KEY_GAP_DP, DEFAULT_KEY_GAP_DP)
            .putBoolean(KEY_LEARN_LOCAL_WORDS, DEFAULT_LEARN_LOCAL_WORDS)
            .putBoolean(KEY_LOCAL_SUGGESTIONS, DEFAULT_LOCAL_SUGGESTIONS)
            .remove(KEY_LEGACY_OUTER_MARGIN_DP)
            .putBoolean(KEY_QUICK_PERIOD, DEFAULT_QUICK_PERIOD)
            .putBoolean(KEY_SHOW_ARROW_ROW, DEFAULT_SHOW_ARROW_ROW)
            .putBoolean(KEY_SHOW_NUMBER_ROW, DEFAULT_SHOW_NUMBER_ROW)
            .putBoolean(KEY_SOUND_ON_KEYPRESS, DEFAULT_SOUND_ON_KEYPRESS)
            .putInt(KEY_STROKE_WIDTH_DP, DEFAULT_STROKE_WIDTH_DP)
            .putBoolean(KEY_VIBRATE_ON_KEYPRESS, DEFAULT_VIBRATE_ON_KEYPRESS)
    }

    fun migrateLayout(prefs: SharedPreferences) {
        if (!prefs.contains(KEY_LEGACY_OUTER_MARGIN_DP)) return
        val legacyMargin = prefs.getInt(KEY_LEGACY_OUTER_MARGIN_DP, DEFAULT_HORIZONTAL_MARGIN_DP)
        val editor = prefs.edit()
        var changed = false
        if (!prefs.contains(KEY_BOTTOM_MARGIN_DP)) {
            editor.putInt(KEY_BOTTOM_MARGIN_DP, legacyMargin)
            changed = true
        }
        if (!prefs.contains(KEY_HORIZONTAL_MARGIN_DP)) {
            editor.putInt(KEY_HORIZONTAL_MARGIN_DP, legacyMargin)
            changed = true
        }
        if (changed) editor.apply()
    }
}

data class KeyboardLayoutSettings(
    val backgroundImageOpacity: Int,
    val backgroundImageUri: String?,
    val bottomMarginDp: Int,
    val cornerRadiusDp: Int,
    val heightPercent: Int,
    val horizontalMarginDp: Int,
    val keyGapDp: Int,
    val learnLocalWords: Boolean,
    val localSuggestions: Boolean,
    val quickPeriod: Boolean,
    val showArrowRow: Boolean,
    val showNumberRow: Boolean,
    val soundOnKeypress: Boolean,
    val strokeWidthDp: Int,
    val vibrateOnKeypress: Boolean
)
