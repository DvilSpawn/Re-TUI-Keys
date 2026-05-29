package com.dvil.retui.keyboard

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RetuiKeyboardService : InputMethodService() {
    private lateinit var prefs: SharedPreferences
    private var theme = ThemeState()
    private var layout = KeyboardLayoutSettings(
        backgroundImageOpacity = KeyboardPrefs.DEFAULT_BACKGROUND_IMAGE_OPACITY,
        backgroundImageUri = null,
        bottomMarginDp = KeyboardPrefs.DEFAULT_BOTTOM_MARGIN_DP,
        cornerRadiusDp = KeyboardPrefs.DEFAULT_CORNER_RADIUS_DP,
        heightPercent = KeyboardPrefs.DEFAULT_HEIGHT_PERCENT,
        horizontalMarginDp = KeyboardPrefs.DEFAULT_HORIZONTAL_MARGIN_DP,
        keyGapDp = KeyboardPrefs.DEFAULT_KEY_GAP_DP,
        learnLocalWords = KeyboardPrefs.DEFAULT_LEARN_LOCAL_WORDS,
        localSuggestions = KeyboardPrefs.DEFAULT_LOCAL_SUGGESTIONS,
        quickPeriod = KeyboardPrefs.DEFAULT_QUICK_PERIOD,
        showArrowRow = KeyboardPrefs.DEFAULT_SHOW_ARROW_ROW,
        showNumberRow = KeyboardPrefs.DEFAULT_SHOW_NUMBER_ROW,
        soundOnKeypress = KeyboardPrefs.DEFAULT_SOUND_ON_KEYPRESS,
        strokeWidthDp = KeyboardPrefs.DEFAULT_STROKE_WIDTH_DP,
        vibrateOnKeypress = KeyboardPrefs.DEFAULT_VIBRATE_ON_KEYPRESS
    )
    private var shifted = false
    private var capsLocked = false
    private var lastShiftTapAtMs = 0L
    private var symbols = false
    private var currentInfo: EditorInfo? = null
    private var contextLabel = "READY"
    private var modeLabel = "COMMAND"
    private var cachedBackgroundBitmap: Bitmap? = null
    private var cachedBackgroundUri: String? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var suggestionStrip: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(KeyboardPrefs.PREFS_NAME, MODE_PRIVATE)
        loadPersistedTheme()
        makeImeWindowTransparent()
    }

    override fun onCreateInputView(): View {
        return buildKeyboardView()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        loadPersistedTheme()
        currentInfo = attribute
        applyEditorInfo(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        makeImeWindowTransparent()
        loadPersistedTheme()
        currentInfo = info
        applyEditorInfo(info)
        setInputView(buildKeyboardView())
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        suggestionStrip = null
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopRepeat()
        suggestionStrip = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        stopRepeat()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setInputView(buildKeyboardView())
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshSuggestionStripSoon()
    }

    override fun onAppPrivateCommand(action: String?, data: Bundle?) {
        super.onAppPrivateCommand(action, data)
        when (action) {
            ACTION_APPLY_CONTEXT, ACTION_APPLY_THEME -> {
                if (data != null) {
                    applyContextBundle(data, persist = true)
                    setInputView(buildKeyboardView())
                }
            }
            ACTION_REFRESH_SETTINGS -> {
                loadPersistedTheme()
                setInputView(buildKeyboardView())
            }
        }
    }

    private fun buildKeyboardView(): View {
        makeImeWindowTransparent()
        layout = KeyboardPrefs.readLayout(prefs)
        return if (isLandscape()) buildLandscapeKeyboard() else buildPortraitKeyboard()
    }

    private fun makeImeWindowTransparent() {
        window?.window?.let { imeWindow ->
            imeWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            imeWindow.decorView?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun buildPortraitKeyboard(): View {
        val root = keyboardRoot(LinearLayout.VERTICAL)
        if (shouldOfferSuggestions()) {
            root.addView(suggestionStripView(), rowParams(32))
        }
        val main = keyboardBody()
        if (usesNumberPad()) {
            addNumberPadRows(main, landscape = false)
        } else {
            addTextRows(main, landscape = false)
        }
        root.addView(main, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun buildLandscapeKeyboard(): View {
        val root = keyboardRoot(LinearLayout.VERTICAL)
        if (shouldOfferSuggestions()) {
            root.addView(suggestionStripView(), rowParams(28))
        }
        val main = keyboardBody()
        if (usesNumberPad()) {
            addNumberPadRows(main, landscape = true)
        } else {
            addTextRows(main, landscape = true)
        }
        root.addView(main, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun keyboardBody(): LinearLayout {
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(layout.keyGapDp), dp(layout.keyGapDp), dp(layout.keyGapDp), dp(layout.keyGapDp))
        return body
    }

    private fun suggestionStripView(): LinearLayout {
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setPadding(dp(layout.keyGapDp + 2), dp(layout.keyGapDp), dp(layout.keyGapDp + 2), dp(layout.keyGapDp))
        strip.background = panel(theme.panelBg, theme.border, 6, notch = true)
        suggestionStrip = strip
        populateSuggestionStrip(strip)
        return strip
    }

    private fun populateSuggestionStrip(strip: LinearLayout) {
        strip.removeAllViews()
        val chips = suggestionChips()
        val gap = dp(layout.keyGapDp + 1)
        chips.forEach { chip ->
            val view = suggestionChipView(chip)
            val params = LinearLayout.LayoutParams(0, -1, chip.weight)
            params.setMargins(gap, 0, gap, 0)
            strip.addView(view, params)
        }
    }

    private fun suggestionChipView(chip: SuggestionChip): TextView {
        val view = keyLabel(chip.label, Gravity.CENTER, keyTextSize(chip.label))
        view.setTextColor(theme.keyText)
        view.background = panel(
            if (chip.action == SuggestionAction.ADD_WORD) brightenColor(theme.keyBg, 1.12f, 28) else theme.keyBg,
            theme.border,
            5
        )
        bindImmediateKey(view, action = {
            when (chip.action) {
                SuggestionAction.ADD_WORD -> {
                    LocalDictionary.learnTypedWord(prefs, chip.word, force = true)
                    refreshSuggestionStripSoon()
                }
                SuggestionAction.COMMIT -> commitSuggestion(chip.word)
            }
        })
        return view
    }

    private fun suggestionChips(): List<SuggestionChip> {
        val currentWord = currentWordBeforeCursor()
        val out = mutableListOf<SuggestionChip>()
        val normalized = LocalDictionary.normalizeWord(currentWord)

        if (
            normalized != null &&
            !LocalDictionary.hasUserWord(prefs, normalized) &&
            !LocalDictionary.isBuiltInWord(normalized)
        ) {
            out.add(SuggestionChip("+ $currentWord", normalized, SuggestionAction.ADD_WORD, 1.15f))
        }

        val remaining = (5 - out.size).coerceAtLeast(1)
        LocalDictionary.suggest(prefs, currentWord, remaining).forEach { suggestion ->
            out.add(SuggestionChip(suggestion, suggestion, SuggestionAction.COMMIT, 1f))
        }

        return out
    }

    private fun controlRail(): LinearLayout {
        val rail = LinearLayout(this)
        rail.orientation = LinearLayout.VERTICAL
        rail.setPadding(dp(layout.keyGapDp + 2), dp(layout.keyGapDp + 2), dp(layout.keyGapDp + 1), dp(layout.keyGapDp + 2))
        rail.background = panel(theme.panelBg, theme.border, 6, notch = true)
        addRailKey(rail, ICON_ESCAPE) { sendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
        addRailKey(rail, ICON_TAB) { commit("\t") }
        addRailKey(rail, ICON_LEFT) { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        addRailKey(rail, ICON_RIGHT) { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
        addRailKey(rail, ICON_HIDE) { requestHideSelf(0) }
        return rail
    }

    private fun addTextRows(parent: LinearLayout, landscape: Boolean) {
        val densePortrait = !landscape && (layout.showNumberRow || layout.showArrowRow)
        val keyHeight = when {
            landscape -> 28
            densePortrait -> 32
            else -> 36
        }
        val bottomHeight = when {
            landscape -> 32
            densePortrait -> 36
            else -> 40
        }
        if (symbols) {
            addKeyRow(parent, symbolRowOne(), keyHeight)
            addKeyRow(parent, symbolRowTwo(), keyHeight)
            addKeyRow(parent, symbolRowThree(), keyHeight)
            addKeyRow(parent, bottomRow(), bottomHeight)
            return
        }

        if (layout.showNumberRow) {
            addKeyRow(parent, numberRow(), if (landscape) 26 else 28)
        }
        addKeyRow(parent, textRow("qwertyuiop", longLabels = if (layout.showNumberRow) null else "1234567890"), keyHeight)
        val homeRowSpacer = if (landscape) 0.35f else 0.55f
        addKeyRow(parent, textRow("asdfghjkl", leadingSpacer = homeRowSpacer, trailingSpacer = homeRowSpacer), keyHeight)
        val third = mutableListOf(KeySpec(shiftLabel(), if (landscape) 1.15f else 1.35f, Special.SHIFT))
        third.addAll(textRow("zxcvbnm"))
        third.add(KeySpec(ICON_BACKSPACE, if (landscape) 1.15f else 1.35f, Special.BACKSPACE))
        addKeyRow(parent, third, keyHeight)
        if (layout.showArrowRow) {
            addKeyRow(parent, arrowRow(), if (landscape) 26 else 28)
        }
        addKeyRow(parent, bottomRow(), bottomHeight)
    }

    private fun addPortraitTextRows(parent: LinearLayout) {
        addKeyRow(parent, textRow("qwertyuiop"), 42)
        addKeyRow(parent, textRow("asdfghjkl", leadingSpacer = 0.55f, trailingSpacer = 0.55f), 42)
        val third = mutableListOf(KeySpec(shiftLabel(), 1.35f, Special.SHIFT))
        third.addAll(textRow("zxcvbnm"))
        third.add(KeySpec(ICON_BACKSPACE, 1.35f, Special.BACKSPACE))
        addKeyRow(parent, third, 42)
        addKeyRow(parent, portraitBottomRow(), 46)
    }

    private fun controlKeysPortrait(): List<KeySpec> {
        return listOf(
            KeySpec(ICON_ESCAPE, 1f, keyCode = KeyEvent.KEYCODE_ESCAPE),
            KeySpec(ICON_TAB, 1f, text = "\t"),
            KeySpec(ICON_LEFT, 1f, keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
            KeySpec(ICON_RIGHT, 1f, keyCode = KeyEvent.KEYCODE_DPAD_RIGHT),
            KeySpec(ICON_UP, 1f, keyCode = KeyEvent.KEYCODE_DPAD_UP),
            KeySpec(ICON_DOWN, 1f, keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
            KeySpec(ICON_HIDE, 1f, Special.HIDE)
        )
    }

    private fun numberRow(landscapeLongPress: Boolean = false): List<KeySpec> {
        if (!landscapeLongPress) {
            return listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { KeySpec(it, text = it) }
        }
        return listOf(
            KeySpec("1", text = "1", longLabel = "/", longText = "/"),
            KeySpec("2", text = "2", longLabel = "-", longText = "-"),
            KeySpec("3", text = "3", longLabel = "_", longText = "_"),
            KeySpec("4", text = "4", longLabel = ".", longText = "."),
            KeySpec("5", text = "5"),
            KeySpec("6", text = "6"),
            KeySpec("7", text = "7"),
            KeySpec("8", text = "8"),
            KeySpec("9", text = "9", longLabel = ICON_UP, longKeyCode = KeyEvent.KEYCODE_DPAD_UP),
            KeySpec("0", text = "0", longLabel = ICON_DOWN, longKeyCode = KeyEvent.KEYCODE_DPAD_DOWN)
        )
    }

    private fun symbolRowOne(): List<KeySpec> {
        return listOf(
            symbolKey("1", "!"),
            symbolKey("2", "@"),
            symbolKey("3", "#"),
            symbolKey("4", "$"),
            symbolKey("5", "%"),
            symbolKey("6", "&"),
            symbolKey("7", "*"),
            symbolKey("8", "("),
            symbolKey("9", ")"),
            symbolKey("0", "^")
        )
    }

    private fun symbolRowTwo(): List<KeySpec> {
        return listOf(
            symbolKey("\\", "|"),
            symbolKey("/", "?"),
            symbolKey("_", "-"),
            symbolKey("=", "+"),
            symbolKey("<", "["),
            symbolKey(">", "]"),
            symbolKey("{", "("),
            symbolKey("}", ")"),
            symbolKey("[", "{"),
            symbolKey("]", "}")
        )
    }

    private fun symbolRowThree(): List<KeySpec> {
        return listOf(
            symbolKey("~", "`"),
            symbolKey("*", "•"),
            symbolKey("'", "\""),
            symbolKey(":", ";"),
            symbolKey("!", "¡"),
            symbolKey("?", "¿"),
            KeySpec(ICON_LEFT, 0.95f, keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
            KeySpec(ICON_UP, 0.95f, keyCode = KeyEvent.KEYCODE_DPAD_UP),
            KeySpec(ICON_DOWN, 0.95f, keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
            KeySpec(ICON_RIGHT, 0.95f, keyCode = KeyEvent.KEYCODE_DPAD_RIGHT),
            KeySpec(ICON_BACKSPACE, 1.25f, Special.BACKSPACE)
        )
    }

    private fun symbolKey(primary: String, alternate: String): KeySpec {
        return KeySpec(primary, text = primary, longLabel = alternate, longText = alternate)
    }

    private fun arrowRow(): List<KeySpec> {
        return listOf(
            KeySpec(ICON_LEFT, 1f, keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
            KeySpec(ICON_UP, 1f, keyCode = KeyEvent.KEYCODE_DPAD_UP),
            KeySpec(ICON_DOWN, 1f, keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
            KeySpec(ICON_RIGHT, 1f, keyCode = KeyEvent.KEYCODE_DPAD_RIGHT)
        )
    }

    private fun bottomRow(): List<KeySpec> {
        val out = mutableListOf(
            KeySpec(if (symbols) "ABC" else "123", 1.2f, Special.SYMBOLS),
            KeySpec(",", 0.75f, text = ","),
            KeySpec("SPACE", if (layout.quickPeriod) 4.8f else 5.55f, Special.SPACE)
        )
        if (layout.quickPeriod) {
            out.add(KeySpec(".", 0.75f, text = "."))
        }
        out.add(KeySpec(enterLabel(), 1.45f, Special.ENTER))
        return out
    }

    private fun portraitBottomRow(): List<KeySpec> {
        return listOf(
            KeySpec("SPACE", 5.2f, Special.SPACE),
            KeySpec("ENTER", 1.8f, Special.ENTER)
        )
    }

    private fun portraitNumpad(): LinearLayout {
        val pad = LinearLayout(this)
        pad.orientation = LinearLayout.VERTICAL
        pad.setPadding(dp(layout.keyGapDp), dp(layout.keyGapDp), dp(layout.keyGapDp), dp(layout.keyGapDp))
        pad.background = panel(theme.panelBg, theme.border, 6, notch = true)

        val rows = if (symbols) portraitSymbolPadRows() else portraitNumberPadRows()
        rows.forEachIndexed { index, row ->
            addKeyRow(pad, row, if (index == rows.lastIndex) 46 else 42)
        }
        return pad
    }

    private fun portraitNumberPadRows(): List<List<KeySpec>> {
        return listOf(
            listOf("7", "8", "9").map { KeySpec(it, text = it) },
            listOf("4", "5", "6").map { KeySpec(it, text = it) },
            listOf("1", "2", "3").map { KeySpec(it, text = it) },
            listOf(
                KeySpec("#+", 1f, Special.SYMBOLS),
                KeySpec("0", 1f, text = "0"),
                KeySpec(".", 1f, text = ".")
            )
        )
    }

    private fun portraitSymbolPadRows(): List<List<KeySpec>> {
        return listOf(
            listOf("/", "-", "_").map { KeySpec(it, text = it) },
            listOf("'", "\"", ":").map { KeySpec(it, text = it) },
            listOf(",", ".", "?").map { KeySpec(it, text = it) },
            listOf(
                KeySpec("ABC", 1f, Special.SYMBOLS),
                KeySpec("@", 1f, text = "@"),
                KeySpec(ICON_BACKSPACE, 1f, Special.BACKSPACE)
            )
        )
    }

    private fun addNumberPadRows(parent: LinearLayout, landscape: Boolean) {
        val keyHeight = if (landscape) 30 else 38
        val bottomHeight = if (landscape) 32 else 40
        val rows = listOf(
            listOf(
                KeySpec("1", text = "1"),
                KeySpec("2", text = "2"),
                KeySpec("3", text = "3"),
                KeySpec("-", text = "-")
            ),
            listOf(
                KeySpec("4", text = "4"),
                KeySpec("5", text = "5"),
                KeySpec("6", text = "6"),
                KeySpec("+", text = "+")
            ),
            listOf(
                KeySpec("7", text = "7"),
                KeySpec("8", text = "8"),
                KeySpec("9", text = "9"),
                KeySpec(ICON_BACKSPACE, special = Special.BACKSPACE)
            ),
            listOf(
                KeySpec(",", text = ","),
                KeySpec("0", text = "0"),
                KeySpec(".", text = "."),
                KeySpec(enterLabel(), special = Special.ENTER)
            )
        )
        rows.forEachIndexed { index, row ->
            addKeyRow(parent, row, if (index == rows.lastIndex) bottomHeight else keyHeight)
        }
    }

    private fun textRow(
        chars: String,
        leadingSpacer: Float = 0f,
        trailingSpacer: Float = 0f,
        longLabels: String? = null
    ): MutableList<KeySpec> {
        val out = mutableListOf<KeySpec>()
        if (leadingSpacer > 0f) out.add(KeySpec("", leadingSpacer, Special.SPACER))
        chars.forEach { ch ->
            val index = chars.indexOf(ch)
            val raw = ch.toString()
            val label = if (isShiftActive()) raw.uppercase() else raw
            val longLabel = longLabels?.getOrNull(index)?.toString()
            out.add(KeySpec(label, text = label, longLabel = longLabel, longText = longLabel))
        }
        if (trailingSpacer > 0f) out.add(KeySpec("", trailingSpacer, Special.SPACER))
        return out
    }

    private fun addKeyRow(parent: LinearLayout, keys: List<KeySpec>, heightDp: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(layout.keyGapDp), dp(1), dp(layout.keyGapDp), dp(1))
        for (key in keys) {
            val params = LinearLayout.LayoutParams(0, -1, key.weight)
            val gap = dp(layout.keyGapDp)
            params.setMargins(gap, gap, gap, gap)
            if (key.special == Special.SPACER) {
                row.addView(View(this), params)
            } else {
                row.addView(keyView(key), params)
            }
        }
        parent.addView(row, rowParams(heightDp))
    }

    private fun addRailKey(parent: LinearLayout, label: String, action: () -> Unit) {
        val view = actionKey(label, action)
        val params = LinearLayout.LayoutParams(-1, 0, 1f)
        params.setMargins(0, dp(layout.keyGapDp + 1), 0, dp(layout.keyGapDp + 1))
        parent.addView(view, params)
    }

    private fun keyView(key: KeySpec): View {
        if (key.longText == null && key.longKeyCode == null) {
            return actionKey(
                key.label,
                action = { handleKey(key) },
                repeatAction = if (key.special == Special.BACKSPACE) { { backspace() } } else null,
                active = key.special == Special.SHIFT && capsLocked
            )
        }

        val view = FrameLayout(this)
        view.background = keyBackground(active = false)
        bindLongPressKey(view, key)

        val primary = keyLabel(key.label, Gravity.CENTER, keyTextSize(key.label))
        view.addView(primary, FrameLayout.LayoutParams(-1, -1))

        if (!key.longLabel.isNullOrBlank()) {
            val hint = keyLabel(key.longLabel, Gravity.TOP or Gravity.END, max(7, keyTextSize(key.label) - 6))
            hint.alpha = 0.78f
            hint.setPadding(0, dp(1), dp(4), 0)
            view.addView(hint, FrameLayout.LayoutParams(-1, -1))
        }

        view.contentDescription = if (key.longLabel.isNullOrBlank()) {
            key.label
        } else {
            "${key.label}, long press ${key.longLabel}"
        }
        return view
    }

    private fun actionKey(
        label: String,
        action: () -> Unit,
        repeatAction: (() -> Unit)? = null,
        active: Boolean = false
    ): TextView {
        val view = keyLabel(label, Gravity.CENTER, keyTextSize(label))
        view.setTextColor(theme.keyText)
        view.background = keyBackground(active)
        bindImmediateKey(view, action, repeatAction)
        return view
    }

    private fun bindImmediateKey(view: View, action: () -> Unit, repeatAction: (() -> Unit)? = null) {
        view.isClickable = true
        view.isFocusable = false
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.isPressed = true
                    pressFeedback(touched)
                    action()
                    repeatAction?.let { startRepeat(it) }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    touched.isPressed = false
                    stopRepeat()
                    true
                }
                else -> true
            }
        }
    }

    private fun bindLongPressKey(view: FrameLayout, key: KeySpec) {
        view.isClickable = true
        view.isFocusable = false
        var longPressHandled = false
        var longPressRunnable: Runnable? = null
        var popup: PopupWindow? = null
        fun clearPopup() {
            popup?.dismiss()
            popup = null
        }
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressHandled = false
                    touched.isPressed = true
                    pressFeedback(touched)
                    longPressRunnable = Runnable {
                        longPressHandled = true
                        popup = showLongPressPreview(view, key)
                        if (layout.vibrateOnKeypress) {
                            vibrateKey(touched, HapticFeedbackConstants.LONG_PRESS, durationMs = 18L)
                        }
                    }
                    repeatHandler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { repeatHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    clearPopup()
                    touched.isPressed = false
                    if (longPressHandled) {
                        handleLongKey(key)
                    } else {
                        handleKey(key)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { repeatHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    clearPopup()
                    touched.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun showLongPressPreview(anchor: View, key: KeySpec): PopupWindow? {
        val label = key.longLabel ?: key.longText ?: return null
        val preview = keyLabel(label, Gravity.CENTER, max(14, keyTextSize(label) + 4))
        preview.setTextColor(theme.keyText)
        preview.background = panel(brightenColor(theme.keyBg, 1.22f, 30), theme.border, 4)
        preview.elevation = dpFloat(8f)
        preview.contentDescription = "Insert $label"

        val width = dp(48)
        val height = dp(44)
        val popup = PopupWindow(preview, width, height, false)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = false
        popup.isClippingEnabled = false
        popup.elevation = dpFloat(8f)

        val xOffset = (anchor.width - width) / 2
        val yOffset = -anchor.height - height - dp(18)
        return try {
            popup.showAsDropDown(anchor, xOffset, yOffset)
            popup
        } catch (_: Exception) {
            null
        }
    }

    private fun keyLabel(label: String, gravity: Int, sizeSp: Int): TextView {
        val view = TextView(this)
        view.text = label
        view.gravity = gravity
        view.includeFontPadding = false
        view.maxLines = 1
        view.isSingleLine = true
        view.typeface = Typeface.MONOSPACE
        view.textSize = sizeSp.toFloat()
        view.setTextColor(theme.keyText)
        return view
    }

    private fun keyTextSize(label: String): Int {
        val baseSize = theme.fontSizeSp.coerceIn(10, 18)
        return when {
            label.length > 5 -> max(9, baseSize - 5)
            label.length >= 4 -> max(10, baseSize - 4)
            label.length >= 3 -> max(10, baseSize - 2)
            else -> baseSize
        }
    }

    private fun usesNumberPad(): Boolean {
        val inputType = currentInfo?.inputType ?: return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
    }

    private fun shouldOfferSuggestions(): Boolean {
        val info = currentInfo ?: return layout.localSuggestions
        return layout.localSuggestions && !usesNumberPad() && !isPasswordField(info)
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val inputType = info.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun enterLabel(): String {
        val action = (currentInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT -> ICON_ENTER_GO
            EditorInfo.IME_ACTION_SEARCH -> ICON_SEARCH
            EditorInfo.IME_ACTION_SEND -> ICON_SEND
            EditorInfo.IME_ACTION_DONE -> ICON_DONE
            else -> ICON_ENTER
        }
    }

    private fun shiftLabel(): String {
        return ICON_SHIFT
    }

    private fun pressFeedback(view: View) {
        if (layout.vibrateOnKeypress) {
            vibrateKey(view, HapticFeedbackConstants.KEYBOARD_TAP, durationMs = 10L)
        }
        if (layout.soundOnKeypress) {
            val audio = getSystemService(AUDIO_SERVICE) as? AudioManager
            audio?.playSoundEffect(AudioManager.FX_KEY_CLICK)
        }
    }

    private fun vibrateKey(view: View, feedbackConstant: Int, durationMs: Long) {
        view.isHapticFeedbackEnabled = true
        val handled = view.performHapticFeedback(
            feedbackConstant,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        if (handled) return

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun keyboardRoot(orientation: Int): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = orientation
        val landscape = isLandscape()
        val sidePadding = dp(layout.horizontalMarginDp)
        val bottomPadding = if (landscape) 0 else dp(layout.bottomMarginDp)
        root.setPadding(sidePadding, 0, sidePadding, bottomPadding)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottomInset = systemBottomInset(insets)
            view.setPadding(sidePadding, 0, sidePadding, bottomPadding + bottomInset)
            insets
        }
        root.post { root.requestApplyInsets() }
        root.background = keyboardBackground() ?: ColorDrawable(Color.TRANSPARENT)
        root.foreground = if (theme.crtFilter) {
            CrtOverlayDrawable(resources.displayMetrics.density, theme.keyText)
        } else {
            null
        }
        return root
    }

    private fun systemBottomInset(insets: WindowInsets): Int {
        val inset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        if (isLandscape()) {
            return inset
        }
        return inset
    }

    private fun keyboardBackground(): Drawable? {
        val bitmap = backgroundBitmap() ?: return null
        return KeyboardBackgroundDrawable(theme.bg, bitmap, layout.backgroundImageOpacity)
    }

    private fun backgroundBitmap(): Bitmap? {
        val uri = layout.backgroundImageUri
        if (uri.isNullOrBlank()) {
            cachedBackgroundUri = null
            cachedBackgroundBitmap = null
            return null
        }
        if (uri == cachedBackgroundUri && cachedBackgroundBitmap != null) {
            return cachedBackgroundBitmap
        }

        return try {
            contentResolver.openInputStream(Uri.parse(uri)).use { input ->
                val decoded = BitmapFactory.decodeStream(input)
                cachedBackgroundUri = uri
                cachedBackgroundBitmap = decoded
                decoded
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun rowParams(heightDp: Int): LinearLayout.LayoutParams {
        val heightPercent = if (isLandscape()) min(layout.heightPercent, LANDSCAPE_MAX_HEIGHT_PERCENT) else layout.heightPercent
        val minHeight = if (isLandscape()) 24 else 28
        val scaledHeight = max(minHeight, (heightDp * heightPercent / 100f).roundToInt())
        val params = LinearLayout.LayoutParams(-1, dp(scaledHeight))
        params.setMargins(0, 0, 0, dp(layout.keyGapDp))
        return params
    }

    private fun panel(fill: Int, stroke: Int, radiusDp: Int, notch: Boolean = false): Drawable {
        val strokeWidthPx = dp(layout.strokeWidthDp)
        val effectiveRadiusDp = layout.cornerRadiusDp
        if (theme.cyberdeckMode && strokeWidthPx > 0 && layout.cornerRadiusDp == 0) {
            return CyberPanelDrawable(
                fill,
                stroke,
                max(1f, dpFloat(layout.strokeWidthDp * if (notch) 1.2f else 1f)),
                notch
            )
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(effectiveRadiusDp).toFloat()
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, stroke)
            }
        }
    }

    private fun keyBackground(active: Boolean): Drawable {
        return panel(keyFillColor(active), theme.border, 5)
    }

    private fun keyFillColor(active: Boolean): Int {
        if (!active) return theme.keyBg
        return brightenColor(theme.keyBg, 1.16f, 36)
    }

    private fun brightenColor(color: Int, factor: Float, alphaBoost: Int): Int {
        val alpha = (Color.alpha(color) + alphaBoost).coerceAtMost(255)
        val red = (Color.red(color) * factor).roundToInt().coerceIn(0, 255)
        val green = (Color.green(color) * factor).roundToInt().coerceIn(0, 255)
        val blue = (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, red, green, blue)
    }

    private fun handleKey(key: KeySpec) {
        when (key.special) {
            Special.BACKSPACE -> backspace()
            Special.ENTER -> enter()
            Special.SHIFT -> handleShift()
            Special.SPACE -> commitFromKey(" ")
            Special.SYMBOLS -> {
                symbols = !symbols
                shifted = false
                capsLocked = false
                setInputView(buildKeyboardView())
            }
            Special.HIDE -> requestHideSelf(0)
            Special.SPACER, null -> {
                when {
                    key.keyCode != null -> sendKeyCode(key.keyCode)
                    key.text != null -> commitFromKey(key.text)
                }
            }
        }
    }

    private fun handleLongKey(key: KeySpec) {
        when {
            key.longKeyCode != null -> sendKeyCode(key.longKeyCode)
            key.longText != null -> commitFromKey(key.longText)
        }
    }

    private fun startRepeat(action: () -> Unit) {
        stopRepeat()
        val runnable = object : Runnable {
            override fun run() {
                action()
                repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, REPEAT_INITIAL_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun commit(value: String) {
        currentInputConnection?.commitText(value, 1)
    }

    private fun commitFromKey(value: String) {
        val finishedWord = if (isWordBoundary(value)) currentWordBeforeCursor() else null
        commit(value)
        learnFinishedWord(finishedWord)
        if (shifted && !capsLocked) {
            shifted = false
            lastShiftTapAtMs = 0L
            setInputView(buildKeyboardView())
        } else {
            refreshSuggestionStripSoon()
        }
    }

    private fun commitSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val currentWord = currentWordBeforeCursor()
        if (currentWord.isNotEmpty()) {
            ic.deleteSurroundingText(currentWord.length, 0)
        }
        ic.commitText("$word ", 1)
        LocalDictionary.recordAcceptedWord(prefs, word)
        if (shifted && !capsLocked) {
            shifted = false
            lastShiftTapAtMs = 0L
            setInputView(buildKeyboardView())
        } else {
            refreshSuggestionStripSoon()
        }
    }

    private fun handleShift() {
        val now = System.currentTimeMillis()
        val doubleTap = now - lastShiftTapAtMs <= SHIFT_DOUBLE_TAP_MS
        lastShiftTapAtMs = now

        when {
            doubleTap -> {
                capsLocked = !capsLocked
                shifted = false
            }
            capsLocked -> {
                capsLocked = false
                shifted = false
            }
            else -> shifted = !shifted
        }
        setInputView(buildKeyboardView())
    }

    private fun isShiftActive(): Boolean {
        return shifted || capsLocked
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        refreshSuggestionStripSoon()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        learnFinishedWord(currentWordBeforeCursor())
        val action = (currentInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendKeyCode(KeyEvent.KEYCODE_ENTER, ic)
        }
        refreshSuggestionStripSoon()
    }

    private fun sendKeyCode(keyCode: Int, ic: InputConnection? = currentInputConnection) {
        ic ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        refreshSuggestionStripSoon()
    }

    private fun learnFinishedWord(word: String?) {
        if (!layout.learnLocalWords || word.isNullOrBlank()) return
        LocalDictionary.learnTypedWord(prefs, word, force = false)
    }

    private fun refreshSuggestionStripSoon() {
        repeatHandler.post { refreshSuggestionStrip() }
    }

    private fun refreshSuggestionStrip() {
        val strip = suggestionStrip ?: return
        if (!shouldOfferSuggestions()) return
        populateSuggestionStrip(strip)
    }

    private fun currentWordBeforeCursor(): String {
        val text = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return ""
        var start = text.length
        while (start > 0 && isWordChar(text[start - 1])) {
            start--
        }
        return text.substring(start)
    }

    private fun isWordBoundary(value: String): Boolean {
        if (value.isEmpty()) return false
        return value.any { !isWordChar(it) }
    }

    private fun isWordChar(char: Char): Boolean {
        return char.isLetter() || char == '\''
    }

    private fun applyEditorInfo(info: EditorInfo?) {
        if (info == null) return
        contextLabel = compactPackageName(info.packageName)
        modeLabel = if (isCommandLikeField(info)) "COMMAND" else "TEXT"
        info.extras?.let { applyContextBundle(it, persist = true) }
        applyPrivateImeOptions(info.privateImeOptions)
    }

    private fun applyPrivateImeOptions(raw: String?) {
        if (raw.isNullOrBlank()) return
        val payload = when {
            raw.startsWith(PRIVATE_OPTIONS_PREFIX) -> raw.substringAfter(':', raw)
            raw.contains("theme_bg=") || raw.contains("module_bg_color=") -> raw
            else -> return
        }
        val bundle = Bundle()
        payload.split(';').forEach { token ->
            val trimmed = token.trim()
            val index = trimmed.indexOf('=')
            if (index <= 0 || index >= trimmed.length - 1) return@forEach
            bundle.putString(trimmed.substring(0, index).trim(), trimmed.substring(index + 1).trim())
        }
        if (bundle.keySet().isNotEmpty()) applyContextBundle(bundle, persist = true)
    }

    private fun applyContextBundle(bundle: Bundle, persist: Boolean) {
        theme = theme.copy(
            bg = readColor(bundle, theme.bg, "theme_bg", "background_color", "theme_background_color"),
            text = readColor(bundle, theme.text, "theme_text", "output_text_color", "theme_text_color"),
            border = readColor(bundle, theme.border, "theme_border", "terminal_border_color", "module_border_color"),
            panelBg = readColor(bundle, theme.panelBg, "terminal_bg", "terminal_window_background_color", "module_bg_color"),
            headerBg = readColor(
                bundle,
                theme.headerBg,
                "terminal_header_background_color",
                "terminal_header_tab_background_color",
                "module_header_bg_color"
            ),
            headerTabBorder = readColor(
                bundle,
                theme.headerTabBorder,
                "terminal_header_border_color",
                "terminal_header_tab_border_color",
                "header_tab_border_color"
            ),
            headerText = readColor(
                bundle,
                theme.headerText,
                "module_text_color",
                "module_header_text_color",
                "terminal_header_text_color",
                "notification_widget_text_color"
            ),
            keyBg = readColor(
                bundle,
                theme.keyBg,
                "module_button_background_color",
                "module_button_bg_color",
                "input_bg_color",
                "input_background_color"
            ),
            keyText = readColor(
                bundle,
                theme.keyText,
                "module_button_text_color",
                "module_text_color",
                "input_text_color",
                "input_text"
            ),
            outputBg = readColor(bundle, theme.outputBg, "output_bg_color", "output_background_color", "output_bg"),
            outputBorder = readColor(bundle, theme.outputBorder, "output_border_color", "output_border", "terminal_border_color"),
            fontSizeSp = readInt(bundle, theme.fontSizeSp, "input_font_size", "keyboard_font_size"),
            dashedBorders = readBoolean(
                bundle,
                theme.dashedBorders,
                "enable_dashed_border",
                "dashed_borders",
                "dashed_border",
                "terminal_dashed_borders"
            ) ?: theme.dashedBorders,
            dashLengthDp = readInt(bundle, theme.dashLengthDp, "dashed_border_dash_length", "dash_length", "terminal_dash_length"),
            dashGapDp = readInt(bundle, theme.dashGapDp, "dashed_border_gap_length", "dash_gap", "terminal_dash_gap"),
            dashedStrokeWidthDp = readFloat(
                bundle,
                theme.dashedStrokeWidthDp,
                "dashed_border_stroke_width",
                "dashed_border_stroke_width_dp",
                "dash_stroke_width"
            ),
            moduleCornerRadiusDp = readInt(
                bundle,
                theme.moduleCornerRadiusDp,
                "module_corner_radius",
                "module_corner_radius_dp",
                "corner_radius",
                "corner_radius_dp"
            ),
            outputCornerRadiusDp = readInt(
                bundle,
                theme.outputCornerRadiusDp,
                "output_corner_radius",
                "output_corner_radius_dp",
                "terminal_corner_radius"
            ),
            headerCornerRadiusDp = readInt(
                bundle,
                theme.headerCornerRadiusDp,
                "header_corner_radius",
                "header_corner_radius_dp",
                "terminal_header_corner_radius"
            ),
            moduleBodyTextSizeSp = readInt(
                bundle,
                theme.moduleBodyTextSizeSp,
                "module_body_text_size",
                "module_body_text_size_sp",
                "module_output_text_size",
                "output_font_size"
            ),
            outputHeaderTextSizeSp = readInt(
                bundle,
                theme.outputHeaderTextSizeSp,
                "output_header_text_size",
                "output_header_text_size_sp",
                "module_header_text_size",
                "module_header_text_size_sp",
                "header_font_size"
            ),
            cyberdeckMode = readBoolean(
                bundle,
                theme.cyberdeckMode,
                "enable_cyberdeck_mode",
                "cyberdeck_mode",
                "cyberdeck",
                "enable_cyberdeck"
            ) ?: theme.cyberdeckMode,
            crtFilter = readBoolean(
                bundle,
                theme.crtFilter,
                "enable_crt_filter",
                "crt_filter",
                "crt",
                "enable_crt"
            ) ?: theme.crtFilter
        )

        readString(bundle, "keyboard_context", "retui_context", "path")?.let { contextLabel = compactContext(it) }
        readString(bundle, "keyboard_mode", "retui_mode", "mode")?.let { modeLabel = it.uppercase().take(14) }
        readBoolean(bundle, null, "keyboard_symbols", "symbols")?.let { symbols = it }

        if (persist) saveTheme()
    }

    private fun loadPersistedTheme() {
        theme = ThemeState(
            bg = prefs.getInt("theme.bg", theme.bg),
            text = prefs.getInt("theme.text", theme.text),
            border = prefs.getInt("theme.border", theme.border),
            panelBg = prefs.getInt("theme.panelBg", theme.panelBg),
            headerBg = prefs.getInt("theme.headerBg", theme.headerBg),
            headerTabBorder = prefs.getInt("theme.headerTabBorder", theme.headerTabBorder),
            headerText = prefs.getInt("theme.headerText", theme.headerText),
            keyBg = prefs.getInt("theme.keyBg", theme.keyBg),
            keyText = prefs.getInt("theme.keyText", theme.keyText),
            outputBg = prefs.getInt("theme.outputBg", theme.outputBg),
            outputBorder = prefs.getInt("theme.outputBorder", theme.outputBorder),
            fontSizeSp = prefs.getInt("theme.fontSizeSp", theme.fontSizeSp),
            dashedBorders = prefs.getBoolean("theme.dashedBorders", theme.dashedBorders),
            dashLengthDp = prefs.getInt("theme.dashLengthDp", theme.dashLengthDp),
            dashGapDp = prefs.getInt("theme.dashGapDp", theme.dashGapDp),
            dashedStrokeWidthDp = prefs.getFloat("theme.dashedStrokeWidthDp", theme.dashedStrokeWidthDp),
            moduleCornerRadiusDp = prefs.getInt("theme.moduleCornerRadiusDp", theme.moduleCornerRadiusDp),
            outputCornerRadiusDp = prefs.getInt("theme.outputCornerRadiusDp", theme.outputCornerRadiusDp),
            headerCornerRadiusDp = prefs.getInt("theme.headerCornerRadiusDp", theme.headerCornerRadiusDp),
            moduleBodyTextSizeSp = prefs.getInt("theme.moduleBodyTextSizeSp", theme.moduleBodyTextSizeSp),
            outputHeaderTextSizeSp = prefs.getInt("theme.outputHeaderTextSizeSp", theme.outputHeaderTextSizeSp),
            cyberdeckMode = prefs.getBoolean("theme.cyberdeckMode", theme.cyberdeckMode),
            crtFilter = prefs.getBoolean("theme.crtFilter", theme.crtFilter)
        )
    }

    private fun saveTheme() {
        prefs.edit()
            .putInt("theme.bg", theme.bg)
            .putInt("theme.text", theme.text)
            .putInt("theme.border", theme.border)
            .putInt("theme.panelBg", theme.panelBg)
            .putInt("theme.headerBg", theme.headerBg)
            .putInt("theme.headerTabBorder", theme.headerTabBorder)
            .putInt("theme.headerText", theme.headerText)
            .putInt("theme.keyBg", theme.keyBg)
            .putInt("theme.keyText", theme.keyText)
            .putInt("theme.outputBg", theme.outputBg)
            .putInt("theme.outputBorder", theme.outputBorder)
            .putInt("theme.fontSizeSp", theme.fontSizeSp)
            .putBoolean("theme.dashedBorders", theme.dashedBorders)
            .putInt("theme.dashLengthDp", theme.dashLengthDp)
            .putInt("theme.dashGapDp", theme.dashGapDp)
            .putFloat("theme.dashedStrokeWidthDp", theme.dashedStrokeWidthDp)
            .putInt("theme.moduleCornerRadiusDp", theme.moduleCornerRadiusDp)
            .putInt("theme.outputCornerRadiusDp", theme.outputCornerRadiusDp)
            .putInt("theme.headerCornerRadiusDp", theme.headerCornerRadiusDp)
            .putInt("theme.moduleBodyTextSizeSp", theme.moduleBodyTextSizeSp)
            .putInt("theme.outputHeaderTextSizeSp", theme.outputHeaderTextSizeSp)
            .putBoolean("theme.cyberdeckMode", theme.cyberdeckMode)
            .putBoolean("theme.crtFilter", theme.crtFilter)
            .apply()
    }

    private fun readColor(bundle: Bundle, fallback: Int, vararg keys: String): Int {
        return parseColorValue(firstValue(bundle, *keys)) ?: fallback
    }

    private fun readInt(bundle: Bundle, fallback: Int, vararg keys: String): Int {
        val value = firstValue(bundle, *keys) ?: return fallback
        if (value is Number) return value.toInt()
        return value.toString().trim().toIntOrNull() ?: fallback
    }

    private fun readFloat(bundle: Bundle, fallback: Float, vararg keys: String): Float {
        val value = firstValue(bundle, *keys) ?: return fallback
        if (value is Number) return value.toFloat()
        return value.toString().trim().toFloatOrNull() ?: fallback
    }

    private fun readString(bundle: Bundle, vararg keys: String): String? {
        val value = firstValue(bundle, *keys) ?: return null
        return value.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun readBoolean(bundle: Bundle, fallback: Boolean?, vararg keys: String): Boolean? {
        val value = firstValue(bundle, *keys) ?: return fallback
        if (value is Boolean) return value
        val raw = value.toString().trim().lowercase()
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> fallback
        }
    }

    @Suppress("DEPRECATION")
    private fun firstValue(bundle: Bundle, vararg keys: String): Any? {
        for (key in keys) {
            if (bundle.containsKey(key)) return bundle.get(key)
        }
        return null
    }

    private fun parseColorValue(value: Any?): Int? {
        if (value == null) return null
        if (value is Number) return value.toInt()
        val raw = value.toString().trim()
        if (raw.isEmpty()) return null
        return try {
            when {
                raw.startsWith("#") -> Color.parseColor(raw)
                raw.startsWith("0x", ignoreCase = true) -> {
                    var parsed = raw.substring(2).toLong(16)
                    if (raw.length <= 8) parsed = parsed or 0xff000000L
                    parsed.toInt()
                }
                else -> raw.toInt()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isCommandLikeField(info: EditorInfo): Boolean {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val klass = info.inputType and InputType.TYPE_MASK_CLASS
        return action == EditorInfo.IME_ACTION_GO ||
            action == EditorInfo.IME_ACTION_SEND ||
            klass == InputType.TYPE_CLASS_TEXT && info.hintText?.contains("$") == true
    }

    private fun inputTypeLabel(inputType: Int): String {
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> "NUMBER"
            InputType.TYPE_CLASS_PHONE -> "PHONE"
            InputType.TYPE_CLASS_DATETIME -> "DATE"
            else -> "TEXT"
        }
    }

    private fun compactPackageName(name: String?): String {
        if (name.isNullOrBlank()) return "READY"
        return name.substringAfterLast('.').uppercase().take(18)
    }

    private fun compactContext(value: String): String {
        val cleaned = value.trim()
        if (cleaned.length <= 24) return cleaned.uppercase()
        return "..." + cleaned.takeLast(21).uppercase()
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun dpFloat(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private data class ThemeState(
        val bg: Int = Color.rgb(2, 6, 4),
        val text: Int = Color.rgb(102, 255, 147),
        val border: Int = Color.rgb(48, 180, 94),
        val panelBg: Int = Color.rgb(8, 18, 12),
        val headerBg: Int = Color.rgb(10, 38, 22),
        val headerTabBorder: Int = Color.rgb(48, 180, 94),
        val headerText: Int = Color.rgb(194, 255, 210),
        val keyBg: Int = Color.rgb(13, 29, 19),
        val keyText: Int = Color.rgb(154, 255, 181),
        val outputBg: Int = 0,
        val outputBorder: Int = 0,
        val fontSizeSp: Int = 14,
        val dashedBorders: Boolean = true,
        val dashLengthDp: Int = 12,
        val dashGapDp: Int = 4,
        val dashedStrokeWidthDp: Float = 1.5f,
        val moduleCornerRadiusDp: Int = 0,
        val outputCornerRadiusDp: Int = 0,
        val headerCornerRadiusDp: Int = 0,
        val moduleBodyTextSizeSp: Int = 14,
        val outputHeaderTextSizeSp: Int = 14,
        val cyberdeckMode: Boolean = false,
        val crtFilter: Boolean = false
    )

    private data class KeySpec(
        val label: String,
        val weight: Float = 1f,
        val special: Special? = null,
        val text: String? = null,
        val keyCode: Int? = null,
        val longLabel: String? = null,
        val longText: String? = null,
        val longKeyCode: Int? = null
    )

    private data class SuggestionChip(
        val label: String,
        val word: String,
        val action: SuggestionAction,
        val weight: Float
    )

    private enum class SuggestionAction {
        ADD_WORD,
        COMMIT
    }

    private enum class Special {
        BACKSPACE,
        ENTER,
        SHIFT,
        SPACE,
        SYMBOLS,
        HIDE,
        SPACER
    }

    private class CyberPanelDrawable(
        private val fillColor: Int,
        private val borderColor: Int,
        strokeWidthPx: Float,
        private val notch: Boolean
    ) : Drawable() {
        private val strokeWidthPx = max(1f, strokeWidthPx)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = fillColor

            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = this.strokeWidthPx
            strokePaint.strokeJoin = Paint.Join.MITER
            strokePaint.color = borderColor

            detailPaint.style = Paint.Style.STROKE
            detailPaint.strokeWidth = max(1f, this.strokeWidthPx / 2f)
            detailPaint.color = Color.argb(
                95,
                Color.red(borderColor),
                Color.green(borderColor),
                Color.blue(borderColor)
            )
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return

            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            val right = bounds.right.toFloat()
            val bottom = bounds.bottom.toFloat()
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val cornerCut = min(min(max(8f, height * 0.34f), width * 0.18f), max(20f, strokeWidthPx * 8f))
            val notchDepth = if (notch) {
                min(min(max(8f, height * 0.22f), width * 0.16f), max(12f, strokeWidthPx * 6f))
            } else {
                0f
            }
            val notchHalfHeight = min(max(1.5f, height * 0.04f), 4f)
            val notchCenter = top + height * 0.52f

            path.reset()
            path.moveTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right, bottom - cornerCut)
            path.lineTo(right - cornerCut, bottom)
            path.lineTo(left, bottom)
            if (notch) {
                path.lineTo(left, notchCenter + notchHalfHeight)
                path.lineTo(left + notchDepth, notchCenter + notchHalfHeight)
                path.lineTo(left + notchDepth, notchCenter - notchHalfHeight)
                path.lineTo(left, notchCenter - notchHalfHeight)
            }
            path.close()

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
            drawDetails(canvas, left, top, right, bottom, width, height, cornerCut, notchDepth)
        }

        private fun drawDetails(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            width: Float,
            height: Float,
            cornerCut: Float,
            notchDepth: Float
        ) {
            if (width < 56f || height < 34f) return
            val inset = max(3f, strokeWidthPx * 2.2f)
            val topRailY = top + inset
            val bottomRailY = bottom - inset
            val leftRailStart = left + notchDepth + inset
            val leftRailEnd = min(right - cornerCut - inset, left + width * 0.42f)
            if (leftRailEnd > leftRailStart + 8f) {
                canvas.drawLine(leftRailStart, topRailY, leftRailEnd, topRailY, detailPaint)
            }
            val rightRailStart = max(left + width * 0.68f, right - cornerCut - width * 0.18f)
            val rightRailEnd = right - inset
            if (rightRailEnd > rightRailStart + 8f) {
                canvas.drawLine(rightRailStart, topRailY, rightRailEnd, topRailY, detailPaint)
            }
            if (height >= 34f) {
                val verticalX = right - inset
                canvas.drawLine(verticalX, top + height * 0.18f, verticalX, bottom - cornerCut - inset, detailPaint)
                canvas.drawLine(left + inset, bottomRailY, left + min(width * 0.22f, 76f), bottomRailY, detailPaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            detailPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            detailPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class KeyboardBackgroundDrawable(
        @Suppress("UNUSED_PARAMETER") fillColor: Int,
        private val bitmap: Bitmap,
        opacityPercent: Int
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val source = Rect()

        init {
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.TRANSPARENT
            imagePaint.alpha = (opacityPercent.coerceIn(0, 100) * 255 / 100f).roundToInt()
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            canvas.drawRect(bounds, fillPaint)
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

            val scale = max(
                bounds.width().toFloat() / bitmap.width.toFloat(),
                bounds.height().toFloat() / bitmap.height.toFloat()
            )
            val srcWidth = (bounds.width() / scale).roundToInt().coerceAtMost(bitmap.width)
            val srcHeight = (bounds.height() / scale).roundToInt().coerceAtMost(bitmap.height)
            val srcLeft = ((bitmap.width - srcWidth) / 2f).roundToInt()
            val srcTop = ((bitmap.height - srcHeight) / 2f).roundToInt()
            source.set(srcLeft, srcTop, srcLeft + srcWidth, srcTop + srcHeight)
            canvas.drawBitmap(bitmap, source, bounds, imagePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            imagePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            imagePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class CrtOverlayDrawable(
        density: Float,
        accentColor: Int
    ) : Drawable() {
        private val scanlineStepPx = max(3f, density * 3f)
        private val scanlineHeightPx = max(1f, density)
        private val beamHeightPx = max(1f, density * 0.5f)
        private val maskStepPx = max(4f, density * 4f)
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scanlinePaint = Paint()
        private val beamPaint = Paint()
        private val maskPaint = Paint()
        private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            tintPaint.style = Paint.Style.FILL
            tintPaint.color = Color.argb(
                10,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )

            scanlinePaint.style = Paint.Style.FILL
            scanlinePaint.color = Color.argb(44, 0, 0, 0)

            beamPaint.style = Paint.Style.FILL
            beamPaint.color = Color.argb(10, 255, 255, 255)

            maskPaint.style = Paint.Style.STROKE
            maskPaint.strokeWidth = 1f
            maskPaint.color = Color.argb(18, 0, 0, 0)

            vignettePaint.style = Paint.Style.FILL
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.isEmpty) {
                vignettePaint.shader = null
                return
            }
            vignettePaint.shader = RadialGradient(
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                max(bounds.width(), bounds.height()) * 0.72f,
                intArrayOf(Color.TRANSPARENT, Color.argb(116, 0, 0, 0)),
                floatArrayOf(0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            canvas.drawRect(bounds, tintPaint)
            var y = bounds.top.toFloat()
            while (y < bounds.bottom) {
                canvas.drawRect(
                    bounds.left.toFloat(),
                    y,
                    bounds.right.toFloat(),
                    y + scanlineHeightPx,
                    scanlinePaint
                )
                canvas.drawRect(
                    bounds.left.toFloat(),
                    y + scanlineHeightPx,
                    bounds.right.toFloat(),
                    y + scanlineHeightPx + beamHeightPx,
                    beamPaint
                )
                y += scanlineStepPx
            }
            var x = bounds.left.toFloat()
            while (x < bounds.right) {
                canvas.drawLine(x, bounds.top.toFloat(), x, bounds.bottom.toFloat(), maskPaint)
                x += maskStepPx
            }
            canvas.drawRect(bounds, vignettePaint)
        }

        override fun setAlpha(alpha: Int) {
            tintPaint.alpha = alpha
            scanlinePaint.alpha = alpha
            beamPaint.alpha = alpha
            maskPaint.alpha = alpha
            vignettePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            tintPaint.colorFilter = colorFilter
            scanlinePaint.colorFilter = colorFilter
            beamPaint.colorFilter = colorFilter
            maskPaint.colorFilter = colorFilter
            vignettePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val ACTION_APPLY_CONTEXT = "com.dvil.retui.keyboard.APPLY_CONTEXT"
        const val ACTION_APPLY_THEME = "com.dvil.retui.keyboard.APPLY_THEME"
        const val ACTION_REFRESH_SETTINGS = "com.dvil.retui.keyboard.REFRESH_SETTINGS"
        const val PRIVATE_OPTIONS_PREFIX = "com.dvil.retui.keyboard"
        private const val REPEAT_INITIAL_DELAY_MS = 260L
        private const val REPEAT_INTERVAL_MS = 42L
        private const val SHIFT_DOUBLE_TAP_MS = 360L
        private const val LANDSCAPE_MAX_HEIGHT_PERCENT = 125
        private const val ICON_BACKSPACE = "DEL"
        private const val ICON_CONTEXT = "⌘"
        private const val ICON_DONE = "✓"
        private const val ICON_DOWN = "↓"
        private const val ICON_ENTER = "↵"
        private const val ICON_ENTER_GO = "→"
        private const val ICON_ESCAPE = "ESC"
        private const val ICON_HIDE = "⌄"
        private const val ICON_LEFT = "←"
        private const val ICON_RIGHT = "→"
        private const val ICON_SEARCH = "⌕"
        private const val ICON_SEND = "➤"
        private const val ICON_SETTINGS = "⚙"
        private const val ICON_SHIFT = "⇧"
        private const val ICON_TAB = "TAB"
        private const val ICON_UP = "↑"
    }
}
