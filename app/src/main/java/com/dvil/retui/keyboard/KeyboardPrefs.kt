package com.dvil.retui.keyboard

import android.content.SharedPreferences

object KeyboardPrefs {
    const val PREFS_NAME = "retui_keyboard"

    const val KEY_BACKGROUND_IMAGE_OPACITY = "layout.backgroundImageOpacity"
    const val KEY_BACKGROUND_IMAGE_URI = "layout.backgroundImageUri"
    const val KEY_BOTTOM_MARGIN_DP = "layout.bottomMarginDp"
    const val KEY_CHARACTER_SIZE_SP = "layout.characterSizeSp"
    const val KEY_CORNER_RADIUS_DP = "layout.cornerRadiusDp"
    const val KEY_HEIGHT_PERCENT = "layout.heightPercent"
    const val KEY_HORIZONTAL_MARGIN_DP = "layout.horizontalMarginDp"
    const val KEY_KEY_GAP_DP = "layout.keyGapDp"
    const val KEY_LANDSCAPE_HEIGHT_PERCENT = "layout.landscapeHeightPercent"
    const val KEY_LEARN_LOCAL_WORDS = "typing.learnLocalWords"
    const val KEY_LEGACY_OUTER_MARGIN_DP = "layout.outerMarginDp"
    const val KEY_LOCAL_SUGGESTIONS = "typing.localSuggestions"
    const val KEY_PORTRAIT_HEIGHT_PERCENT = "layout.portraitHeightPercent"
    const val KEY_QUICK_PERIOD = "layout.quickPeriod"
    const val KEY_SHOW_ARROW_ROW = "layout.showArrowRow"
    const val KEY_SHOW_NUMBER_ROW = "layout.showNumberRow"
    const val KEY_SHOW_PORTRAIT_SPECIAL_KEYS = "layout.showPortraitSpecialKeys"
    const val KEY_SOUND_ON_KEYPRESS = "layout.soundOnKeypress"
    const val KEY_SPLIT_KEYBOARD = "layout.splitKeyboard"
    const val KEY_STROKE_WIDTH_DP = "layout.strokeWidthDp"
    const val KEY_THEME_COLORS_OVERRIDDEN = "theme.colorsOverridden"
    const val KEY_THEME_LAUNCHER_AVAILABLE = "theme.launcher.available"
    const val KEY_THEME_LAUNCHER_UPDATED_AT = "theme.launcher.updatedAt"
    const val KEY_THEME_LAUNCHER_PREFIX = "theme.launcher."
    const val KEY_VIBRATE_ON_KEYPRESS = "layout.vibrateOnKeypress"

    const val DEFAULT_BACKGROUND_IMAGE_OPACITY = 55
    const val DEFAULT_BOTTOM_MARGIN_DP = 4
    const val DEFAULT_CHARACTER_SIZE_SP = 14
    const val DEFAULT_CORNER_RADIUS_DP = 0
    const val DEFAULT_HEIGHT_PERCENT = 100
    const val DEFAULT_HORIZONTAL_MARGIN_DP = 4
    const val DEFAULT_KEY_GAP_DP = 2
    const val DEFAULT_LANDSCAPE_HEIGHT_PERCENT = 100
    const val DEFAULT_LEARN_LOCAL_WORDS = true
    const val DEFAULT_LOCAL_SUGGESTIONS = true
    const val DEFAULT_PORTRAIT_HEIGHT_PERCENT = DEFAULT_HEIGHT_PERCENT
    const val DEFAULT_QUICK_PERIOD = true
    const val DEFAULT_SHOW_ARROW_ROW = false
    const val DEFAULT_SHOW_NUMBER_ROW = false
    const val DEFAULT_SHOW_PORTRAIT_SPECIAL_KEYS = false
    const val DEFAULT_SOUND_ON_KEYPRESS = false
    const val DEFAULT_SPLIT_KEYBOARD = false
    const val DEFAULT_STROKE_WIDTH_DP = 1
    const val DEFAULT_VIBRATE_ON_KEYPRESS = false

    fun readLayout(prefs: SharedPreferences): KeyboardLayoutSettings {
        val legacyMargin = prefs.getInt(KEY_LEGACY_OUTER_MARGIN_DP, DEFAULT_HORIZONTAL_MARGIN_DP)
        val legacyHeight = prefs.getInt(KEY_HEIGHT_PERCENT, DEFAULT_HEIGHT_PERCENT).coerceIn(80, 180)
        return KeyboardLayoutSettings(
            backgroundImageOpacity = prefs.getInt(
                KEY_BACKGROUND_IMAGE_OPACITY,
                DEFAULT_BACKGROUND_IMAGE_OPACITY
            ).coerceIn(0, 100),
            backgroundImageUri = prefs.getString(KEY_BACKGROUND_IMAGE_URI, null)?.takeIf { it.isNotBlank() },
            bottomMarginDp = prefs.getInt(KEY_BOTTOM_MARGIN_DP, legacyMargin).coerceIn(0, 64),
            characterSizeSp = prefs.getInt(KEY_CHARACTER_SIZE_SP, DEFAULT_CHARACTER_SIZE_SP).coerceIn(10, 24),
            cornerRadiusDp = prefs.getInt(KEY_CORNER_RADIUS_DP, DEFAULT_CORNER_RADIUS_DP).coerceIn(0, 18),
            horizontalMarginDp = prefs.getInt(KEY_HORIZONTAL_MARGIN_DP, legacyMargin).coerceIn(0, 48),
            keyGapDp = prefs.getInt(KEY_KEY_GAP_DP, DEFAULT_KEY_GAP_DP).coerceIn(0, 8),
            landscapeHeightPercent = prefs.getInt(
                KEY_LANDSCAPE_HEIGHT_PERCENT,
                legacyHeight
            ).coerceIn(80, 180),
            learnLocalWords = prefs.getBoolean(KEY_LEARN_LOCAL_WORDS, DEFAULT_LEARN_LOCAL_WORDS),
            localSuggestions = prefs.getBoolean(KEY_LOCAL_SUGGESTIONS, DEFAULT_LOCAL_SUGGESTIONS),
            portraitHeightPercent = prefs.getInt(
                KEY_PORTRAIT_HEIGHT_PERCENT,
                legacyHeight
            ).coerceIn(80, 180),
            quickPeriod = prefs.getBoolean(KEY_QUICK_PERIOD, DEFAULT_QUICK_PERIOD),
            showArrowRow = prefs.getBoolean(KEY_SHOW_ARROW_ROW, DEFAULT_SHOW_ARROW_ROW),
            showNumberRow = prefs.getBoolean(KEY_SHOW_NUMBER_ROW, DEFAULT_SHOW_NUMBER_ROW),
            showPortraitSpecialKeys = prefs.getBoolean(
                KEY_SHOW_PORTRAIT_SPECIAL_KEYS,
                DEFAULT_SHOW_PORTRAIT_SPECIAL_KEYS
            ),
            soundOnKeypress = prefs.getBoolean(KEY_SOUND_ON_KEYPRESS, DEFAULT_SOUND_ON_KEYPRESS),
            splitKeyboard = prefs.getBoolean(KEY_SPLIT_KEYBOARD, DEFAULT_SPLIT_KEYBOARD),
            strokeWidthDp = prefs.getInt(KEY_STROKE_WIDTH_DP, DEFAULT_STROKE_WIDTH_DP).coerceIn(0, 5),
            vibrateOnKeypress = prefs.getBoolean(KEY_VIBRATE_ON_KEYPRESS, DEFAULT_VIBRATE_ON_KEYPRESS)
        )
    }

    fun resetLayout(editor: SharedPreferences.Editor) {
        editor
            .putInt(KEY_BACKGROUND_IMAGE_OPACITY, DEFAULT_BACKGROUND_IMAGE_OPACITY)
            .remove(KEY_BACKGROUND_IMAGE_URI)
            .putInt(KEY_BOTTOM_MARGIN_DP, DEFAULT_BOTTOM_MARGIN_DP)
            .putInt(KEY_CHARACTER_SIZE_SP, DEFAULT_CHARACTER_SIZE_SP)
            .putInt(KEY_CORNER_RADIUS_DP, DEFAULT_CORNER_RADIUS_DP)
            .putInt(KEY_HORIZONTAL_MARGIN_DP, DEFAULT_HORIZONTAL_MARGIN_DP)
            .putInt(KEY_KEY_GAP_DP, DEFAULT_KEY_GAP_DP)
            .putInt(KEY_LANDSCAPE_HEIGHT_PERCENT, DEFAULT_LANDSCAPE_HEIGHT_PERCENT)
            .putBoolean(KEY_LEARN_LOCAL_WORDS, DEFAULT_LEARN_LOCAL_WORDS)
            .putBoolean(KEY_LOCAL_SUGGESTIONS, DEFAULT_LOCAL_SUGGESTIONS)
            .putInt(KEY_PORTRAIT_HEIGHT_PERCENT, DEFAULT_PORTRAIT_HEIGHT_PERCENT)
            .remove(KEY_HEIGHT_PERCENT)
            .remove(KEY_LEGACY_OUTER_MARGIN_DP)
            .putBoolean(KEY_QUICK_PERIOD, DEFAULT_QUICK_PERIOD)
            .putBoolean(KEY_SHOW_ARROW_ROW, DEFAULT_SHOW_ARROW_ROW)
            .putBoolean(KEY_SHOW_NUMBER_ROW, DEFAULT_SHOW_NUMBER_ROW)
            .putBoolean(KEY_SHOW_PORTRAIT_SPECIAL_KEYS, DEFAULT_SHOW_PORTRAIT_SPECIAL_KEYS)
            .putBoolean(KEY_SOUND_ON_KEYPRESS, DEFAULT_SOUND_ON_KEYPRESS)
            .putBoolean(KEY_SPLIT_KEYBOARD, DEFAULT_SPLIT_KEYBOARD)
            .putInt(KEY_STROKE_WIDTH_DP, DEFAULT_STROKE_WIDTH_DP)
            .putBoolean(KEY_VIBRATE_ON_KEYPRESS, DEFAULT_VIBRATE_ON_KEYPRESS)
    }

    fun migrateLayout(prefs: SharedPreferences) {
        val editor = prefs.edit()
        var changed = false

        if (prefs.contains(KEY_LEGACY_OUTER_MARGIN_DP)) {
            val legacyMargin = prefs.getInt(KEY_LEGACY_OUTER_MARGIN_DP, DEFAULT_HORIZONTAL_MARGIN_DP)
            if (!prefs.contains(KEY_BOTTOM_MARGIN_DP)) {
                editor.putInt(KEY_BOTTOM_MARGIN_DP, legacyMargin)
                changed = true
            }
            if (!prefs.contains(KEY_HORIZONTAL_MARGIN_DP)) {
                editor.putInt(KEY_HORIZONTAL_MARGIN_DP, legacyMargin)
                changed = true
            }
        }

        if (prefs.contains(KEY_HEIGHT_PERCENT)) {
            val legacyHeight = prefs.getInt(KEY_HEIGHT_PERCENT, DEFAULT_HEIGHT_PERCENT).coerceIn(80, 180)
            if (!prefs.contains(KEY_PORTRAIT_HEIGHT_PERCENT)) {
                editor.putInt(KEY_PORTRAIT_HEIGHT_PERCENT, legacyHeight)
                changed = true
            }
            if (!prefs.contains(KEY_LANDSCAPE_HEIGHT_PERCENT)) {
                editor.putInt(KEY_LANDSCAPE_HEIGHT_PERCENT, legacyHeight)
                changed = true
            }
        }

        if (
            !prefs.getBoolean(KEY_THEME_COLORS_OVERRIDDEN, false) &&
            !prefs.getBoolean(KEY_THEME_LAUNCHER_AVAILABLE, false) &&
            THEME_SNAPSHOT_SUFFIXES.any { prefs.contains("theme.$it") }
        ) {
            val currentValues = prefs.all
            THEME_SNAPSHOT_SUFFIXES.forEach { suffix ->
                val oldKey = "theme.$suffix"
                val value = currentValues[oldKey] ?: return@forEach
                val newKey = KEY_THEME_LAUNCHER_PREFIX + suffix
                when (value) {
                    is Boolean -> editor.putBoolean(newKey, value)
                    is Int -> editor.putInt(newKey, value)
                    is Float -> editor.putFloat(newKey, value)
                    is String -> editor.putString(newKey, value)
                }
            }
            editor
                .putBoolean(KEY_THEME_LAUNCHER_AVAILABLE, true)
                .putLong(KEY_THEME_LAUNCHER_UPDATED_AT, System.currentTimeMillis())
            changed = true
        }

        if (changed) editor.apply()
    }

    private val THEME_SNAPSHOT_SUFFIXES = arrayOf(
        "bg",
        "text",
        "border",
        "panelBg",
        "headerBg",
        "headerTabBorder",
        "headerText",
        "keyBg",
        "keyText",
        "specialKeyBg",
        "specialKeyText",
        "outputBg",
        "outputBorder",
        "fontSizeSp",
        "dashedBorders",
        "dashLengthDp",
        "dashGapDp",
        "dashedStrokeWidthDp",
        "moduleCornerRadiusDp",
        "outputCornerRadiusDp",
        "headerCornerRadiusDp",
        "moduleBodyTextSizeSp",
        "outputHeaderTextSizeSp",
        "cyberdeckMode",
        "crtFilter"
    )
}

data class KeyboardLayoutSettings(
    val backgroundImageOpacity: Int,
    val backgroundImageUri: String?,
    val bottomMarginDp: Int,
    val characterSizeSp: Int,
    val cornerRadiusDp: Int,
    val horizontalMarginDp: Int,
    val keyGapDp: Int,
    val landscapeHeightPercent: Int,
    val learnLocalWords: Boolean,
    val localSuggestions: Boolean,
    val portraitHeightPercent: Int,
    val quickPeriod: Boolean,
    val showArrowRow: Boolean,
    val showNumberRow: Boolean,
    val showPortraitSpecialKeys: Boolean,
    val soundOnKeypress: Boolean,
    val splitKeyboard: Boolean,
    val strokeWidthDp: Int,
    val vibrateOnKeypress: Boolean
)
