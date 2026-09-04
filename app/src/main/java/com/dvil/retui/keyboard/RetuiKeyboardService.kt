package com.dvil.retui.keyboard

import com.dvil.retui.contract.RetuiVisualContract
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
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
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RetuiKeyboardService : InputMethodService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var frameRenderer: LauncherFrameRenderer
    private var theme = ThemeState()
    private var layout = KeyboardLayoutSettings(
        acceptLauncherFrames = KeyboardPrefs.DEFAULT_ACCEPT_LAUNCHER_FRAMES,
        backgroundImageOpacity = KeyboardPrefs.DEFAULT_BACKGROUND_IMAGE_OPACITY,
        backgroundImageUri = null,
        bottomMarginDp = KeyboardPrefs.DEFAULT_BOTTOM_MARGIN_DP,
        clipboardAutoSave = KeyboardPrefs.DEFAULT_CLIPBOARD_AUTO_SAVE,
        characterSizeSp = KeyboardPrefs.DEFAULT_CHARACTER_SIZE_SP,
        clipboardRetentionDays = KeyboardPrefs.DEFAULT_CLIPBOARD_RETENTION_DAYS,
        cornerRadiusDp = KeyboardPrefs.DEFAULT_CORNER_RADIUS_DP,
        fontUri = null,
        horizontalMarginDp = KeyboardPrefs.DEFAULT_HORIZONTAL_MARGIN_DP,
        keyGapDp = KeyboardPrefs.DEFAULT_KEY_GAP_DP,
        landscapeHeightPercent = KeyboardPrefs.DEFAULT_LANDSCAPE_HEIGHT_PERCENT,
        glideDiagnostics = KeyboardPrefs.DEFAULT_GLIDE_DIAGNOSTICS,
        glideTyping = KeyboardPrefs.DEFAULT_GLIDE_TYPING,
        deleteWholeWord = KeyboardPrefs.DEFAULT_DELETE_WHOLE_WORD,
        doubleSpacePeriod = KeyboardPrefs.DEFAULT_DOUBLE_SPACE_PERIOD,
        learnLocalWords = KeyboardPrefs.DEFAULT_LEARN_LOCAL_WORDS,
        localSuggestions = KeyboardPrefs.DEFAULT_LOCAL_SUGGESTIONS,
        portraitHeightPercent = KeyboardPrefs.DEFAULT_PORTRAIT_HEIGHT_PERCENT,
        quickPeriod = KeyboardPrefs.DEFAULT_QUICK_PERIOD,
        showArrowRow = KeyboardPrefs.DEFAULT_SHOW_ARROW_ROW,
        showNumberRow = KeyboardPrefs.DEFAULT_SHOW_NUMBER_ROW,
        showPortraitSpecialKeys = KeyboardPrefs.DEFAULT_SHOW_PORTRAIT_SPECIAL_KEYS,
        soundOnKeypress = KeyboardPrefs.DEFAULT_SOUND_ON_KEYPRESS,
        splitKeyboard = KeyboardPrefs.DEFAULT_SPLIT_KEYBOARD,
        strokeWidthDp = KeyboardPrefs.DEFAULT_STROKE_WIDTH_DP,
        vibrateOnKeypress = KeyboardPrefs.DEFAULT_VIBRATE_ON_KEYPRESS
    )
    private var shifted = false
    private var capsLocked = false
    private var ctrlLatched = false
    private var altLatched = false
    private var metaLatched = false
    private var lastShiftTapAtMs = 0L
    private var symbols = false
    private var currentInfo: EditorInfo? = null
    private var installedLanguagePacks: List<LanguagePack> = emptyList()
    private var activeLanguagePack: LanguagePack? = null
    private var contextLabel = "READY"
    private var modeLabel = "COMMAND"
    private var cachedBackgroundBitmap: Bitmap? = null
    private var cachedBackgroundUri: String? = null
    private var cachedFontUri: String? = null
    private var cachedTypeface: Typeface? = null
    private var activeKeyPreview: PopupWindow? = null
    private var launcherShortcuts: LauncherShortcutContract.State? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var suggestionRefreshPosted = false
    private var lastBottomSafeInsetPx = 0
    private var lastKeyboardViewSignature: KeyboardViewSignature? = null
    private val suggestionRefreshRunnable = Runnable {
        suggestionRefreshPosted = false
        refreshSuggestionStrip()
    }
    private var suggestionStrip: LinearLayout? = null
    private var pendingAddWord: String? = null
    private var localWordBeforeCursor = ""
    private val glideKeyHits = mutableListOf<GlideKeyHit>()
    private var glideTrailView: GlideTrailView? = null
    private var emojiMode = false
    private var clipboardMode = false
    private var clipboardClearPending = false
    private var emojiCategoryIndex = 0
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceButton: ImageButton? = null
    private var voiceListening = false
    private var acceptVoiceResult = false

    private val voiceRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            val wasActive = acceptVoiceResult
            finishVoiceSession()
            if (wasActive && error != SpeechRecognizer.ERROR_CLIENT) {
                Toast.makeText(this@RetuiKeyboardService, "No speech recognized", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onResults(results: Bundle?) {
            val shouldCommit = acceptVoiceResult
            finishVoiceSession()
            if (!shouldCommit) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) return
            currentInputConnection?.commitText(text, 1)
            localWordBeforeCursor = ""
            pendingAddWord = null
            refreshSuggestionStripSoon()
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(KeyboardPrefs.PREFS_NAME, MODE_PRIVATE)
        KeyboardPrefs.migrateLayout(prefs)
        frameRenderer = LauncherFrameRenderer.shared(this, prefs)
        launcherShortcuts = LauncherShortcutContract.read(prefs)
        LocalDictionary.preload(applicationContext)
        reloadLanguagePacks()
        loadPersistedTheme()
        makeImeWindowTransparent()
    }

    override fun onCreateInputView(): View {
        return buildKeyboardView()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        loadPersistedTheme()
        reloadLanguagePacks()
        currentInfo = attribute
        if (!restarting) resetTransientLayoutState()
        applyEditorInfo(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        makeImeWindowTransparent()
        loadPersistedTheme()
        currentInfo = info
        applyEditorInfo(info)
        val languageChanged = reloadLanguagePacks()
        val nextLayout = KeyboardPrefs.readLayout(prefs)
        layout = nextLayout
        captureClipboardIfEnabled()
        val shiftedForEmptyInput = armShiftForEmptyInputIfNeeded()
        if (languageChanged || shiftedForEmptyInput || keyboardViewSignature(nextLayout) != lastKeyboardViewSignature) {
            setInputView(buildKeyboardView())
        } else {
            refreshSuggestionStripSoon()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelVoiceInput()
        dismissActiveKeyPreview()
        stopRepeat()
        cancelSuggestionRefresh()
        suggestionStrip = null
        resetTransientLayoutState()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        cancelVoiceInput()
        dismissActiveKeyPreview()
        stopRepeat()
        cancelSuggestionRefresh()
        suggestionStrip = null
        resetTransientLayoutState()
        super.onFinishInput()
    }

    override fun onDestroy() {
        cancelVoiceInput()
        speechRecognizer?.destroy()
        speechRecognizer = null
        dismissActiveKeyPreview()
        stopRepeat()
        cancelSuggestionRefresh()
        cachedBackgroundBitmap?.recycle()
        cachedBackgroundBitmap = null
        cachedBackgroundUri = null
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
            ACTION_APPLY_CONTEXT -> {
                if (data != null) {
                    if (applyContextBundle(data, ThemeSource.LAUNCHER)) {
                        setInputView(buildKeyboardView())
                    }
                }
            }
            ACTION_APPLY_THEME -> {
                if (data != null) {
                    if (applyContextBundle(data, ThemeSource.PREVIEW)) {
                        setInputView(buildKeyboardView())
                    }
                }
            }
            ACTION_REFRESH_SETTINGS -> {
                val themeChanged = loadPersistedTheme()
                val languageChanged = reloadLanguagePacks()
                val nextLayout = KeyboardPrefs.readLayout(prefs)
                if (themeChanged || languageChanged || nextLayout != layout) {
                    if (nextLayout.acceptLauncherFrames != layout.acceptLauncherFrames) frameRenderer.reload()
                    setInputView(buildKeyboardView())
                }
            }
        }
    }

    private fun buildKeyboardView(): View {
        makeImeWindowTransparent()
        layout = KeyboardPrefs.readLayout(prefs)
        lastKeyboardViewSignature = keyboardViewSignature(layout)
        suggestionStrip = null
        voiceButton = null
        glideKeyHits.clear()
        glideTrailView = null
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
        val categoryInHeader = emojiMode && shouldOfferSuggestions()
        if (categoryInHeader) {
            root.addView(emojiCategoryRow(), fixedRowParams(35))
        } else if (shouldOfferSuggestions()) {
            root.addView(suggestionStripView(), fixedRowParams(35))
        }
        val main = normalKeyboardBody(landscape = false)
        val body = when {
            emojiMode -> emojiBodyLayer(main, categoryInHeader)
            clipboardMode -> clipboardBodyLayer(main)
            else -> glideLayer(main)
        }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun buildLandscapeKeyboard(): View {
        val root = keyboardRoot(LinearLayout.VERTICAL)
        val categoryInHeader = emojiMode && shouldOfferSuggestions()
        if (categoryInHeader) {
            root.addView(emojiCategoryRow(), fixedRowParams(28))
        } else if (shouldOfferSuggestions()) {
            root.addView(suggestionStripView(), fixedRowParams(28))
        }
        val main = normalKeyboardBody(landscape = true)
        val body = when {
            emojiMode -> emojiBodyLayer(main, categoryInHeader)
            clipboardMode -> clipboardBodyLayer(main)
            else -> main
        }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun normalKeyboardBody(landscape: Boolean): LinearLayout {
        val main = keyboardBody()
        if (usesNumberPad()) {
            addNumberPadRows(main, landscape)
        } else if (landscape && layout.splitKeyboard && activeLanguagePack == null) {
            addSplitTextRows(main)
        } else {
            addTextRows(main, landscape)
        }
        return main
    }

    private fun emojiBodyLayer(normalBody: LinearLayout, categoryInHeader: Boolean): View {
        val frame = GlideLayerFrame(this)
        normalBody.visibility = View.INVISIBLE
        frame.addView(normalBody, FrameLayout.LayoutParams(-1, -2))
        frame.addView(emojiBodyOverlay(categoryInHeader), FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun emojiBodyOverlay(categoryInHeader: Boolean): LinearLayout {
        val body = keyboardBody()
        body.background = panel(theme.panelBg, theme.border, 6, notch = true)
        if (!categoryInHeader) {
            body.addView(emojiCategoryRow(), rowParams(emojiCategoryHeightDp()))
        }
        body.addView(emojiGridScroll(), weightedRowParams())
        body.addView(emojiControlRow(), rowParams(emojiControlHeightDp()))
        return body
    }

    private fun clipboardBodyLayer(normalBody: LinearLayout): View {
        val frame = GlideLayerFrame(this)
        normalBody.visibility = View.INVISIBLE
        frame.addView(normalBody, FrameLayout.LayoutParams(-1, -2))
        frame.addView(clipboardBodyOverlay(), FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun clipboardBodyOverlay(): LinearLayout {
        val body = keyboardBody()
        body.background = frameRenderer.drawable(
            RetuiVisualContract.FRAME_ROLE_KEYBOARD,
            theme.panelBg,
            panel(theme.panelBg, theme.border, 6, notch = true)
        )
        body.addView(clipboardListScroll(), weightedRowParams())
        body.addView(clipboardControlRow(), rowParams(emojiControlHeightDp()))
        return body
    }

    private fun keyboardBody(): LinearLayout {
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(layout.keyGapDp), dp(layout.keyGapDp), dp(layout.keyGapDp), dp(layout.keyGapDp))
        return body
    }

    private fun emojiCategoryRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(layout.keyGapDp), dp(1), dp(layout.keyGapDp), dp(1))
        row.addView(
            emojiActionCell("ABC", active = false, contentDescription = "Return to letters") {
                closeEmojiMode()
            },
            LinearLayout.LayoutParams(0, -1, 1.2f)
        )
        repeat(emojiCategoryCount()) { index ->
            row.addView(
                emojiActionCell(emojiCategoryLabel(index), active = index == emojiCategoryIndex, contentDescription = "Emoji category ${emojiCategoryLabel(index)}") {
                    selectEmojiCategory(index)
                },
                LinearLayout.LayoutParams(0, -1, 1f)
            )
        }
        row.addView(
            emojiActionCell(
                ICON_BACKSPACE,
                active = false,
                contentDescription = "Backspace",
                action = { backspace() },
                repeatAction = { backspace() }
            ),
            LinearLayout.LayoutParams(0, -1, 1.1f)
        )
        return row
    }

    private fun emojiGridScroll(): ScrollView {
        val scroll = ScrollView(this)
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = true
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.background = ColorDrawable(Color.TRANSPARENT)

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(2), dp(2), dp(2), dp(2))

        val columns = emojiColumnCount()
        activeEmojiList().chunked(columns).forEach { emojiRow ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            row.setPadding(dp(layout.keyGapDp), dp(1), dp(layout.keyGapDp), dp(1))
            repeat(columns) { column ->
                val emoji = emojiRow.getOrNull(column)
                val cell = if (emoji != null) {
                    emojiCell(emoji)
                } else {
                    View(this)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, dp(emojiCellSizeDp())))
        }

        scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
        return scroll
    }

    private fun emojiControlRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(layout.keyGapDp), dp(1), dp(layout.keyGapDp), dp(1))
        row.addView(
            emojiActionCell("ABC", active = false, contentDescription = "Return to letters") {
                closeEmojiMode()
            },
            LinearLayout.LayoutParams(0, -1, 1.25f)
        )
        row.addView(
            emojiActionCell(ICON_EMOJI, active = true, contentDescription = "Emoji keyboard") {},
            LinearLayout.LayoutParams(0, -1, 0.9f)
        )
        row.addView(
            emojiActionCell("SPACE", active = false, contentDescription = "Space") {
                commit(" ")
            },
            LinearLayout.LayoutParams(0, -1, 3.6f)
        )
        row.addView(
            emojiActionCell(
                ICON_BACKSPACE,
                active = false,
                contentDescription = "Backspace",
                action = { backspace() },
                repeatAction = { backspace() }
            ),
            LinearLayout.LayoutParams(0, -1, 1.05f)
        )
        row.addView(
            emojiActionCell(enterLabel(), active = false, contentDescription = "Enter") {
                enter()
            },
            LinearLayout.LayoutParams(0, -1, 1.1f)
        )
        return row
    }

    private fun emojiCell(emoji: String): TextView {
        val cell = keyLabel(emoji, Gravity.CENTER, max(22, theme.fontSizeSp + 10))
        cell.typeface = Typeface.DEFAULT
        cell.contentDescription = "Insert emoji $emoji"
        cell.setTextColor(theme.keyText)
        cell.background = ColorDrawable(Color.TRANSPARENT)
        bindTapKey(cell) {
            EmojiRecentsStore.record(prefs, emoji)
            commitEmoji(emoji)
        }
        return cell
    }

    private fun clipboardListScroll(): ScrollView {
        val scroll = ScrollView(this)
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = true
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.background = ColorDrawable(Color.TRANSPARENT)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(2), dp(2), dp(2), dp(2))

        val items = LocalClipboardStore.items(prefs, layout.clipboardRetentionDays)
        if (items.isEmpty()) {
            list.addView(clipboardEmptyCell(), LinearLayout.LayoutParams(-1, dp(clipboardRowHeightDp())))
        } else {
            items.forEach { item ->
                list.addView(clipboardItemCell(item), LinearLayout.LayoutParams(-1, dp(clipboardRowHeightDp())))
            }
        }

        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        return scroll
    }

    private fun clipboardControlRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(layout.keyGapDp), dp(1), dp(layout.keyGapDp), dp(1))
        if (clipboardClearPending) {
            row.addView(
                emojiActionCell("CANCEL", active = false, contentDescription = "Cancel clearing clipboard") {
                    clipboardClearPending = false
                    setInputView(buildKeyboardView())
                },
                LinearLayout.LayoutParams(0, -1, 1f)
            )
            row.addView(
                emojiActionCell("CLEAR ALL?", active = true, contentDescription = "Confirm clear all clipboard history") {
                    LocalClipboardStore.clear(prefs)
                    clipboardClearPending = false
                    setInputView(buildKeyboardView())
                },
                LinearLayout.LayoutParams(0, -1, 1.4f)
            )
            return row
        }
        row.addView(
            emojiActionCell("ABC", active = false, contentDescription = "Return to letters") {
                closeClipboardMode()
            },
            LinearLayout.LayoutParams(0, -1, 1.15f)
        )
        row.addView(
            emojiActionCell("+", active = false, contentDescription = "Save clipboard") {
                saveCurrentClipboardText()
            },
            LinearLayout.LayoutParams(0, -1, 0.8f)
        )
        row.addView(
            emojiActionCell(ICON_CLIPBOARD, active = true, contentDescription = "Clipboard") {},
            LinearLayout.LayoutParams(0, -1, 0.9f)
        )
        row.addView(
            emojiActionCell("CLR", active = false, contentDescription = "Clear clipboard") {
                clipboardClearPending = true
                setInputView(buildKeyboardView())
            },
            LinearLayout.LayoutParams(0, -1, 1f)
        )
        row.addView(
            emojiActionCell("SPACE", active = false, contentDescription = "Space") {
                commit(" ")
            },
            LinearLayout.LayoutParams(0, -1, 2.8f)
        )
        row.addView(
            emojiActionCell(
                ICON_BACKSPACE,
                active = false,
                contentDescription = "Backspace",
                action = { backspace() },
                repeatAction = { backspace() }
            ),
            LinearLayout.LayoutParams(0, -1, 1f)
        )
        return row
    }

    private fun clipboardItemCell(item: LocalClipboardItem): TextView {
        val cell = keyLabel(clipboardPreview(item.text), Gravity.CENTER_VERTICAL or Gravity.START, theme.fontSizeSp.coerceIn(11, 18))
        cell.contentDescription = "Paste clipboard item"
        cell.setTextColor(theme.keyText)
        cell.maxLines = 2
        cell.ellipsize = TextUtils.TruncateAt.END
        cell.setPadding(dp(10), 0, dp(10), 0)
        cell.background = ColorDrawable(Color.TRANSPARENT)
        bindTapKey(cell) {
            pasteClipboardItem(item.text)
        }
        cell.setOnLongClickListener {
            showClipboardItemActions(cell, item)
            true
        }
        return cell
    }

    private fun showClipboardItemActions(anchor: View, item: LocalClipboardItem) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = frameRenderer.drawable(
                RetuiVisualContract.FRAME_ROLE_KEYBOARD,
                theme.panelBg,
                panel(theme.panelBg, theme.border, 4)
            )
            elevation = dpFloat(8f)
        }
        val width = dp(96)
        val height = dp(46)
        val popup = PopupWindow(row, width, height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isClippingEnabled = false
            elevation = dpFloat(8f)
        }
        fun action(icon: Int, description: String, run: () -> Unit) = ImageButton(this).apply {
            contentDescription = description
            setImageResource(icon)
            imageTintList = null
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ColorDrawable(Color.TRANSPARENT)
            setOnClickListener {
                run()
                popup.dismiss()
                setInputView(buildKeyboardView())
            }
        }
        row.addView(action(if (item.pinned) R.drawable.ic_clipboard_unpin else R.drawable.ic_clipboard_pin, if (item.pinned) "Unpin clipboard item" else "Pin clipboard item") {
            LocalClipboardStore.setPinned(prefs, item.text, !item.pinned)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(action(R.drawable.ic_clipboard_delete, "Delete clipboard item") {
            LocalClipboardStore.remove(prefs, item.text)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        popup.showAsDropDown(anchor, (anchor.width - width) / 2, -anchor.height - height - dp(6))
    }

    private fun clipboardEmptyCell(): TextView {
        val cell = keyLabel("EMPTY", Gravity.CENTER, theme.fontSizeSp.coerceIn(11, 18))
        cell.setTextColor(theme.keyText)
        cell.background = keyVisualInset(panel(theme.keyBg, theme.border, 5))
        return cell
    }

    private fun clipboardPreview(text: String): String {
        return text.replace('\n', ' ').replace('\r', ' ').trim().ifBlank { " " }
    }

    private fun emojiActionCell(
        label: String,
        active: Boolean,
        contentDescription: String,
        repeatAction: (() -> Unit)? = null,
        action: () -> Unit
    ): TextView {
        val cell = keyLabel(label, Gravity.CENTER, if (label.length <= 2) max(14, theme.fontSizeSp + 2) else keyTextSize(label))
        if (label.length <= 2) cell.typeface = Typeface.DEFAULT
        cell.contentDescription = contentDescription
        cell.setTextColor(if (active) theme.specialKeyText else theme.keyText)
        cell.background = if (active) specialKeyBackground(active = true) else keyBackground(active = false)
        bindImmediateKey(cell, action = action, repeatAction = repeatAction, dismissEmojiOnDown = false)
        return cell
    }

    private fun activeEmojiList(): List<String> {
        val safeIndex = emojiCategoryIndex.coerceIn(0, emojiCategoryCount() - 1)
        if (safeIndex != emojiCategoryIndex) emojiCategoryIndex = safeIndex
        return if (safeIndex == 0) {
            EmojiRecentsStore.items(prefs)
        } else {
            EmojiData.CATEGORIES[safeIndex - 1].second
        }
    }

    private fun emojiCategoryCount(): Int {
        return EmojiData.CATEGORIES.size + 1
    }

    private fun emojiCategoryLabel(index: Int): String {
        return if (index == 0) ICON_RECENTS else EmojiData.CATEGORIES[index - 1].first
    }

    private fun emojiColumnCount(): Int {
        return if (isLandscape()) 12 else 8
    }

    private fun emojiCellSizeDp(): Int {
        return if (isLandscape()) 34 else 42
    }

    private fun emojiCategoryHeightDp(): Int {
        return if (isLandscape()) 28 else 34
    }

    private fun emojiControlHeightDp(): Int {
        return if (isLandscape()) 30 else 42
    }

    private fun clipboardRowHeightDp(): Int {
        return if (isLandscape()) 38 else 46
    }

    private fun glideLayer(main: LinearLayout): View {
        if (!layout.glideTyping || activeLanguagePack != null || isLandscape() || symbols || usesNumberPad()) return main
        val frame = GlideLayerFrame(this)
        frame.addView(main, FrameLayout.LayoutParams(-1, -2))
        glideTrailView = GlideTrailView(this, theme.border, resources.displayMetrics.density).also { trail ->
            trail.isClickable = false
            trail.isFocusable = false
            frame.addView(trail, FrameLayout.LayoutParams(-1, -1))
        }
        return frame
    }

    private fun suggestionStripView(): LinearLayout {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.HORIZONTAL
        outer.gravity = Gravity.CENTER_VERTICAL
        outer.setPadding(dp(layout.keyGapDp + 2), dp(layout.keyGapDp), dp(layout.keyGapDp + 2), dp(layout.keyGapDp))
        outer.background = frameRenderer.drawable(
            RetuiVisualContract.FRAME_ROLE_KEYBOARD,
            theme.panelBg,
            panel(theme.panelBg, theme.border, 6, notch = true)
        )

        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        if (activeLanguagePack?.rtl == true) {
            chips.layoutDirection = View.LAYOUT_DIRECTION_RTL
            chips.textDirection = View.TEXT_DIRECTION_RTL
        }
        suggestionStrip = chips
        outer.addView(chips, LinearLayout.LayoutParams(0, -1, 1f))
        outer.addView(voiceStripButton(), LinearLayout.LayoutParams(dp(if (isLandscape()) 34 else 40), -1))
        outer.addView(clipboardStripButton(), LinearLayout.LayoutParams(dp(if (isLandscape()) 34 else 40), -1))
        populateSuggestionStrip(chips)
        return outer
    }

    private fun clipboardStripButton(): TextView {
        val button = keyLabel(ICON_CLIPBOARD, Gravity.CENTER, max(14, theme.fontSizeSp + 2))
        button.contentDescription = "Clipboard"
        button.setTextColor(if (clipboardMode) theme.specialKeyText else theme.keyText)
        button.background = keyVisualInset(if (clipboardMode) specialKeyBackground(active = true) else panel(theme.keyBg, theme.border, 5))
        bindImmediateKey(button, action = {
            if (clipboardMode) closeClipboardMode() else openClipboardMode()
        }, dismissEmojiOnDown = false)
        return button
    }

    private fun voiceStripButton(): ImageButton {
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        voiceButton = button
        updateVoiceButton()
        bindImmediateKey(button, action = { toggleVoiceInput() }, dismissEmojiOnDown = false)
        return button
    }

    private fun toggleVoiceInput() {
        when {
            voiceListening -> {
                voiceListening = false
                updateVoiceButton()
                speechRecognizer?.stopListening()
            }
            acceptVoiceResult -> cancelVoiceInput()
            else -> startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        if (currentInfo?.let(::isPasswordField) == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestHideSelf(0)
            startActivity(Intent(this, MicrophonePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            })
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice typing unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = speechRecognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(voiceRecognitionListener)
                speechRecognizer = it
            }
        } catch (_: RuntimeException) {
            Toast.makeText(this, "Voice typing unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        acceptVoiceResult = true
        voiceListening = true
        updateVoiceButton()
        try {
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: RuntimeException) {
            finishVoiceSession()
            Toast.makeText(this, "Voice typing unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelVoiceInput() {
        val wasActive = acceptVoiceResult
        finishVoiceSession()
        if (wasActive) speechRecognizer?.cancel()
    }

    private fun finishVoiceSession() {
        acceptVoiceResult = false
        voiceListening = false
        updateVoiceButton()
    }

    private fun updateVoiceButton() {
        val button = voiceButton ?: return
        button.contentDescription = when {
            voiceListening -> "Stop voice typing"
            acceptVoiceResult -> "Cancel voice typing"
            else -> "Start voice typing"
        }
        button.setColorFilter(if (acceptVoiceResult) theme.specialKeyText else theme.keyText)
        button.background = keyVisualInset(
            if (acceptVoiceResult) specialKeyBackground(active = true) else panel(theme.keyBg, theme.border, 5)
        )
    }

    private fun populateSuggestionStrip(strip: LinearLayout) {
        val chips = suggestionChips()
        chips.forEachIndexed { index, chip ->
            val view = (strip.getChildAt(index) as? TextView) ?: suggestionChipView().also {
                strip.addView(it)
            }
            configureSuggestionChipView(view, chip)
            val params = (view.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(0, -1)
            params.width = 0
            params.height = -1
            params.weight = chip.weight
            view.layoutParams = params
        }
        while (strip.childCount > chips.size) {
            strip.removeViewAt(strip.childCount - 1)
        }
    }

    private fun suggestionChipView(): TextView {
        return keyLabel("", Gravity.CENTER, theme.fontSizeSp.coerceIn(10, 18))
    }

    private fun configureSuggestionChipView(view: TextView, chip: SuggestionChip) {
        view.text = chip.label
        view.textSize = keyTextSize(chip.label).toFloat()
        view.gravity = Gravity.CENTER
        view.setTextColor(theme.keyText)
        view.background = ColorDrawable(Color.TRANSPARENT)
        bindImmediateKey(view, action = {
            when (chip.action) {
                SuggestionAction.ADD_WORD -> {
                    activeLearnTypedWord(chip.word, force = true)
                    pendingAddWord = null
                    refreshSuggestionStripSoon()
                }
                SuggestionAction.COMMIT -> commitSuggestion(chip.word)
                SuggestionAction.COMMIT_NEXT_WORD -> commitNextWordAfterActive(chip.word)
            }
        })
    }

    private fun suggestionChips(): List<SuggestionChip> {
        val currentWord = currentWordBeforeCursor()
        val out = mutableListOf<SuggestionChip>()
        val hasActiveWord = currentWord.any(::isWordChar)
        val normalized = activeNormalizeWord(currentWord)

        if (hasActiveWord) {
            pendingAddWord = null
            if (normalized != null && activeContainsKnownWord(normalized)) {
                val seen = HashSet<String>()
                activeSuggestCurrentWordAlternatives(currentWord, 3).forEach { suggestion ->
                    val key = activeNormalizeWord(suggestion) ?: suggestion.lowercase()
                    if (seen.add("current:$key")) {
                        out.add(SuggestionChip(suggestion, suggestion, SuggestionAction.COMMIT, 1f))
                    }
                }
                if (activeLanguagePack != null) {
                    activeSuggest(currentWord, 4).forEach { suggestion ->
                        val key = activeNormalizeWord(suggestion) ?: suggestion.lowercase()
                        if (seen.add("current:$key")) {
                            out.add(SuggestionChip(suggestion, suggestion, SuggestionAction.COMMIT, 1f))
                        }
                    }
                }
                activeSuggestNextWords(normalized, 2).forEach { suggestion ->
                    val key = activeNormalizeWord(suggestion) ?: suggestion.lowercase()
                    if (seen.add("next:$key")) {
                        out.add(SuggestionChip(suggestion, suggestion, SuggestionAction.COMMIT_NEXT_WORD, 1f))
                    }
                }
            } else {
                val suggestions = activeSuggest(currentWord, 5)
                suggestions.forEach { suggestion ->
                    out.add(SuggestionChip(suggestion, suggestion, SuggestionAction.COMMIT, 1f))
                }
                if (
                    out.size < 5 &&
                    suggestions.isEmpty() &&
                    normalized != null &&
                    normalized.length >= ACTIVE_ADD_WORD_MIN_LENGTH &&
                    !activeContainsKnownWord(normalized)
                ) {
                    out.add(SuggestionChip("+ ${currentWord.trim()}", normalized, SuggestionAction.ADD_WORD, 1.15f))
                }
            }
        } else {
            val pending = pendingAddWord
            if (pending != null && !activeContainsKnownWord(pending)) {
                out.add(SuggestionChip("+ ${activeDisplayWord(pending)}", pending, SuggestionAction.ADD_WORD, 1.15f))
            } else {
                activeSuggestNextWords(previousWordBeforeCursor(), 5).forEach { suggestion ->
                    out.add(SuggestionChip(suggestion, suggestion, SuggestionAction.COMMIT, 1f))
                }
            }
        }

        return out
    }

    private fun addTextRows(parent: LinearLayout, landscape: Boolean) {
        val densePortrait = !landscape && (
            layout.showNumberRow ||
                layout.showArrowRow ||
                layout.showPortraitSpecialKeys
            )
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
        if (!landscape && layout.showPortraitSpecialKeys) {
            addKeyRow(parent, portraitSpecialKeyRow(), if (densePortrait) 28 else 30)
        }
        if (symbols) {
            addKeyRow(parent, symbolRowOne(), keyHeight)
            addKeyRow(parent, symbolRowTwo(), keyHeight)
            addKeyRow(parent, symbolRowThree(), keyHeight)
            addKeyRow(parent, bottomRow(), bottomHeight)
            return
        }

        val pack = activeLanguagePack
        if (pack != null) {
            addLanguagePackRows(parent, pack, keyHeight, bottomHeight, landscape)
            return
        }

        if (layout.showNumberRow) {
            addKeyRow(parent, numberRow(), if (landscape) 26 else 28)
        }
        addKeyRow(parent, textRow("qwertyuiop", longLabels = if (layout.showNumberRow) null else "1234567890"), keyHeight)
        val homeRowSpacer = if (landscape) 0.35f else 0.55f
        addKeyRow(
            parent,
            textRow(
                "asdfghjkl",
                leadingSpacer = homeRowSpacer,
                trailingSpacer = homeRowSpacer,
                edgeAliases = !landscape
            ),
            keyHeight
        )
        val third = mutableListOf(KeySpec(ICON_SHIFT, if (landscape) 1.15f else 1.35f, Special.SHIFT))
        third.addAll(textRow("zxcvbnm"))
        third.add(KeySpec(ICON_BACKSPACE, if (landscape) 1.15f else 1.35f, Special.BACKSPACE))
        addKeyRow(parent, third, keyHeight)
        if (layout.showArrowRow) {
            addKeyRow(parent, arrowRow(), if (landscape) 26 else 28)
        }
        addKeyRow(parent, bottomRow(), bottomHeight)
    }

    private fun addLanguagePackRows(
        parent: LinearLayout,
        pack: LanguagePack,
        keyHeight: Int,
        bottomHeight: Int,
        landscape: Boolean
    ) {
        if (layout.showNumberRow) {
            val digits = pack.digits.ifEmpty { listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0") }
            addKeyRow(parent, digits.map { KeySpec(it, text = it) }, if (landscape) 26 else 28)
        }
        addKeyRow(parent, packKeyRow(pack.rows[0]), keyHeight)
        addKeyRow(
            parent,
            mutableListOf(KeySpec("", 0.45f, Special.SPACER)).apply {
                addAll(packKeyRow(pack.rows[1]))
                add(KeySpec("", 0.45f, Special.SPACER))
            },
            keyHeight
        )
        val third = mutableListOf<KeySpec>()
        pack.joiners.firstOrNull()?.let { joiner ->
            third.add(KeySpec(pack.joinerLabel, 1.15f, text = joiner.toString(), specialStyle = true))
        }
        third.addAll(packKeyRow(pack.rows[2]))
        third.add(KeySpec(ICON_BACKSPACE, 1.25f, Special.BACKSPACE))
        addKeyRow(parent, third, keyHeight)
        if (layout.showArrowRow) {
            addKeyRow(parent, arrowRow(), if (landscape) 26 else 28)
        }
        addKeyRow(parent, bottomRow(), bottomHeight)
    }

    private fun packKeyRow(keys: List<String>): List<KeySpec> = keys.map { KeySpec(it, text = it) }

    private fun addSplitTextRows(parent: LinearLayout) {
        val keyHeight = 28
        val bottomHeight = 32
        if (symbols) {
            addSplitSymbolRows(parent, keyHeight, bottomHeight)
            return
        }

        if (layout.showNumberRow) {
            addSplitKeyRow(
                parent = parent,
                left = numberRow().take(5),
                center = splitSpecialRow(0),
                right = numberRow().drop(5),
                heightDp = 26
            )
        }

        addSplitKeyRow(
            parent = parent,
            left = textRow("qwert", longLabels = if (layout.showNumberRow) null else "12345"),
            center = splitSpecialRow(if (layout.showNumberRow) 1 else 0),
            right = textRow("yuiop", longLabels = if (layout.showNumberRow) null else "67890"),
            heightDp = keyHeight
        )
        addSplitKeyRow(
            parent = parent,
            left = textRow("asdfg"),
            center = splitSpecialRow(if (layout.showNumberRow) 2 else 1),
            right = textRow("ghjkl"),
            heightDp = keyHeight
        )
        addSplitKeyRow(
            parent = parent,
            left = mutableListOf(KeySpec(ICON_SHIFT, 1.15f, Special.SHIFT)).apply {
                addAll(textRow("zxcv"))
            },
            center = if (layout.showNumberRow) emptyList() else splitSpecialRow(2),
            right = textRow("vbnm").apply {
                add(KeySpec(ICON_BACKSPACE, 1.15f, Special.BACKSPACE))
            },
            heightDp = keyHeight
        )
        if (layout.showArrowRow) {
            addSplitKeyRow(
                parent = parent,
                left = listOf(
                    KeySpec(ICON_LEFT, 1f, keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
                    KeySpec(ICON_UP, 1f, keyCode = KeyEvent.KEYCODE_DPAD_UP)
                ),
                center = emptyList(),
                right = listOf(
                    KeySpec(ICON_DOWN, 1f, keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
                    KeySpec(ICON_RIGHT, 1f, keyCode = KeyEvent.KEYCODE_DPAD_RIGHT)
                ),
                heightDp = 26,
                leftWeight = 5f,
                centerWeight = 2.4f,
                rightWeight = 5f
            )
        }
        addSplitBottomRow(parent, bottomHeight)
    }

    private fun addSplitSymbolRows(parent: LinearLayout, keyHeight: Int, bottomHeight: Int) {
        val first = symbolRowOne()
        val second = symbolRowTwo()
        val third = symbolRowThree()
        addSplitKeyRow(parent, first.take(5), splitSpecialRow(0), first.drop(5), keyHeight)
        addSplitKeyRow(parent, second.take(5), splitSpecialRow(1), second.drop(5), keyHeight)
        addSplitKeyRow(parent, third.take(5), splitSpecialRow(2), third.drop(5), keyHeight)
        addSplitBottomRow(parent, bottomHeight)
    }

    private fun splitSpecialRow(index: Int): List<KeySpec> {
        return when (index) {
            0 -> listOf(
                KeySpec(ICON_ESCAPE, 1f, keyCode = KeyEvent.KEYCODE_ESCAPE, specialStyle = true),
                KeySpec(ICON_TAB, 1f, keyCode = KeyEvent.KEYCODE_TAB, specialStyle = true)
            )
            1 -> listOf(
                KeySpec("CTRL", 1f, Special.CTRL, specialStyle = true),
                KeySpec("ALT", 1f, Special.ALT, specialStyle = true)
            )
            else -> listOf(
                KeySpec("SUPER", 1f, Special.SUPER, specialStyle = true),
                KeySpec("DEL", 1f, Special.FORWARD_DELETE, specialStyle = true)
            )
        }
    }

    private fun portraitSpecialKeyRow(): List<KeySpec> {
        return listOf(
            KeySpec(ICON_ESCAPE, 1f, keyCode = KeyEvent.KEYCODE_ESCAPE, specialStyle = true),
            KeySpec(ICON_TAB, 1f, keyCode = KeyEvent.KEYCODE_TAB, specialStyle = true),
            KeySpec("CTRL", 1f, Special.CTRL, specialStyle = true),
            KeySpec("ALT", 1f, Special.ALT, specialStyle = true),
            KeySpec("SUPER", 1.15f, Special.SUPER, specialStyle = true),
            KeySpec("DEL", 1f, Special.FORWARD_DELETE, specialStyle = true)
        )
    }

    private fun addSplitBottomRow(parent: LinearLayout, heightDp: Int) {
        val right = mutableListOf<KeySpec>()
        if (layout.quickPeriod) right.add(periodKey(0.9f))
        right.add(enterKey(1.35f))
        val left = mutableListOf(KeySpec(if (symbols) "ABC" else "123", 1.2f, Special.SYMBOLS))
        languageSwitchKey()?.let(left::add)
        left.add(commaKey(0.8f))
        addSplitKeyRow(
            parent = parent,
            left = left,
            center = listOf(KeySpec("SPACE", 1f, Special.SPACE)),
            right = right,
            heightDp = heightDp,
            leftWeight = 2.6f,
            centerWeight = 5.2f,
            rightWeight = 2.6f
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
            symbolKey("/", activeLanguagePack?.questionMark ?: "?"),
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
            symbolKey(activeLanguagePack?.questionMark ?: "?", "¿"),
            KeySpec(ICON_DPAD, 2.2f, Special.DIRECTION_PAD, specialStyle = true),
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
        val hasLanguageSwitch = installedLanguagePacks.isNotEmpty()
        val out = mutableListOf(KeySpec(if (symbols) "ABC" else "123", 1.2f, Special.SYMBOLS))
        languageSwitchKey()?.let(out::add)
        out.add(commaKey(0.75f))
        out.add(
            KeySpec(
                activeLanguagePack?.spaceLabel ?: "SPACE",
                if (hasLanguageSwitch) 4.1f else if (layout.quickPeriod) 4.8f else 5.55f,
                Special.SPACE
            )
        )
        if (layout.quickPeriod) {
            out.add(periodKey(0.75f))
        }
        out.add(enterKey(1.45f))
        return out
    }

    private fun languageSwitchKey(): KeySpec? {
        if (installedLanguagePacks.isEmpty()) return null
        val target = nextLanguagePack()
        return KeySpec(target?.switchLabel ?: "EN", 1f, Special.LANGUAGE, specialStyle = true)
    }

    private fun nextLanguagePack(): LanguagePack? {
        val languages = listOf<LanguagePack?>(null) + installedLanguagePacks
        val currentId = activeLanguagePack?.id ?: LanguagePackManager.ENGLISH_ID
        val currentIndex = languages.indexOfFirst {
            (it?.id ?: LanguagePackManager.ENGLISH_ID) == currentId
        }.coerceAtLeast(0)
        return languages[(currentIndex + 1) % languages.size]
    }

    private fun switchLanguage() {
        val target = nextLanguagePack()
        LanguagePackManager.setActive(prefs, target?.id ?: LanguagePackManager.ENGLISH_ID)
        activeLanguagePack = target
        symbols = false
        shifted = false
        capsLocked = false
        pendingAddWord = null
        localWordBeforeCursor = ""
        clearLatchedModifiers()
        setInputView(buildKeyboardView())
        refreshSuggestionStripSoon()
    }

    private fun reloadLanguagePacks(): Boolean {
        val oldSignature = activeLanguagePack?.let { "${it.id}:${it.version}" } ?: LanguagePackManager.ENGLISH_ID
        installedLanguagePacks = LanguagePackManager.installedPacks(this)
        val requestedId = LanguagePackManager.activeId(prefs)
        activeLanguagePack = if (requestedId == LanguagePackManager.ENGLISH_ID) {
            null
        } else {
            installedLanguagePacks.firstOrNull { it.id == requestedId }.also { pack ->
                if (pack == null) LanguagePackManager.setActive(prefs, LanguagePackManager.ENGLISH_ID)
            }
        }
        val nextSignature = activeLanguagePack?.let { "${it.id}:${it.version}" } ?: LanguagePackManager.ENGLISH_ID
        return oldSignature != nextSignature
    }

    private fun portraitBottomRow(): List<KeySpec> {
        return listOf(
            KeySpec("SPACE", 5.2f, Special.SPACE),
            enterKey(1.8f)
        )
    }

    private fun portraitNumberPadRows(): List<List<KeySpec>> {
        return listOf(
            listOf("7", "8", "9").map { KeySpec(it, text = it) },
            listOf("4", "5", "6").map { KeySpec(it, text = it) },
            listOf("1", "2", "3").map { KeySpec(it, text = it) },
            listOf(
                KeySpec("#+", 1f, Special.SYMBOLS),
                KeySpec("0", 1f, text = "0"),
                periodKey()
            )
        )
    }

    private fun portraitSymbolPadRows(): List<List<KeySpec>> {
        return listOf(
            listOf("/", "-", "_").map { KeySpec(it, text = it) },
            listOf("'", "\"", ":").map { KeySpec(it, text = it) },
            listOf(commaKey(), periodKey(), KeySpec("?", text = "?")),
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
                commaKey(),
                KeySpec("0", text = "0"),
                periodKey(),
                enterKey()
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
        longLabels: String? = null,
        edgeAliases: Boolean = false
    ): MutableList<KeySpec> {
        val out = mutableListOf<KeySpec>()
        if (leadingSpacer > 0f) {
            val first = shiftedChar(chars.firstOrNull())
            out.add(
                if (edgeAliases && first != null) {
                    KeySpec("", leadingSpacer, text = first, edgeAlias = true)
                } else {
                    KeySpec("", leadingSpacer, Special.SPACER)
                }
            )
        }
        chars.forEach { ch ->
            val index = chars.indexOf(ch)
            val label = shiftedChar(ch) ?: ch.toString()
            val longLabel = longLabels?.getOrNull(index)?.toString()
            val accentVariants = accentVariantsFor(ch).let { variants ->
                if (variants.isEmpty()) variants else variants + listOfNotNull(longLabel)
            }
            out.add(
                KeySpec(
                    label = label,
                    text = label,
                    longLabel = if (accentVariants.isNotEmpty()) "..." else longLabel,
                    longText = if (accentVariants.isEmpty()) longLabel else null,
                    accentVariants = accentVariants
                )
            )
        }
        if (trailingSpacer > 0f) {
            val last = shiftedChar(chars.lastOrNull())
            out.add(
                if (edgeAliases && last != null) {
                    KeySpec("", trailingSpacer, text = last, edgeAlias = true)
                } else {
                    KeySpec("", trailingSpacer, Special.SPACER)
                }
            )
        }
        return out
    }

    private fun shiftedChar(ch: Char?): String? {
        ch ?: return null
        val raw = ch.toString()
        return if (isShiftActive()) raw.uppercase() else raw
    }

    private fun accentVariantsFor(ch: Char): List<String> {
        val variants = when (ch.lowercaseChar()) {
            'a' -> listOf("á", "à", "â", "ä", "æ", "å", "ã")
            'c' -> listOf("ç")
            'e' -> listOf("é", "è", "ê", "ë")
            'i' -> listOf("í", "ì", "î", "ï")
            'n' -> listOf("ñ")
            'o' -> listOf("ó", "ò", "ô", "ö", "œ", "ø", "õ")
            's' -> listOf("ß")
            'u' -> listOf("ú", "ù", "û", "ü")
            else -> emptyList()
        }
        return if (ch.isUpperCase()) {
            variants.map { variant -> if (variant == "ß") "ẞ" else variant.uppercase() }
        } else {
            variants
        }
    }

    private fun commaKey(weight: Float = 1f): KeySpec {
        val value = activeLanguagePack?.comma ?: ","
        return KeySpec(value, weight, text = value, longLabel = ICON_SETTINGS, longSpecial = Special.SETTINGS)
    }

    private fun periodKey(weight: Float = 1f): KeySpec {
        val value = activeLanguagePack?.period ?: "."
        return KeySpec(value, weight, text = value, longLabel = ICON_EMOJI, longSpecial = Special.EMOJI_PICKER)
    }

    private fun enterKey(weight: Float = 1f): KeySpec {
        return if (enterUsesEditorAction()) {
            KeySpec(enterLabel(), weight, Special.ENTER, longLabel = ICON_ENTER, longSpecial = Special.SHIFT_ENTER)
        } else {
            KeySpec(enterLabel(), weight, Special.ENTER)
        }
    }

    private fun addKeyRow(parent: LinearLayout, keys: List<KeySpec>, heightDp: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(layout.keyGapDp), dp(1), dp(layout.keyGapDp), dp(1))
        for (key in keys) {
            val params = LinearLayout.LayoutParams(0, -1, key.weight)
            if (key.special == Special.SPACER) {
                row.addView(View(this), params)
            } else {
                row.addView(keyView(key), params)
            }
        }
        parent.addView(row, rowParams(heightDp))
    }

    private fun addSplitKeyRow(
        parent: LinearLayout,
        left: List<KeySpec>,
        center: List<KeySpec>,
        right: List<KeySpec>,
        heightDp: Int,
        leftWeight: Float = 5f,
        centerWeight: Float = 2.4f,
        rightWeight: Float = 5f
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(layout.keyGapDp), dp(1), dp(layout.keyGapDp), dp(1))
        row.addView(splitKeyCluster(left), LinearLayout.LayoutParams(0, -1, leftWeight))
        row.addView(splitKeyCluster(center), LinearLayout.LayoutParams(0, -1, centerWeight))
        row.addView(splitKeyCluster(right), LinearLayout.LayoutParams(0, -1, rightWeight))
        parent.addView(row, rowParams(heightDp))
    }

    private fun splitKeyCluster(keys: List<KeySpec>): LinearLayout {
        val cluster = LinearLayout(this)
        cluster.orientation = LinearLayout.HORIZONTAL
        cluster.gravity = Gravity.CENTER
        for (key in keys) {
            val params = LinearLayout.LayoutParams(0, -1, key.weight)
            if (key.special == Special.SPACER) {
                cluster.addView(View(this), params)
            } else {
                cluster.addView(keyView(key), params)
            }
        }
        return cluster
    }

    private fun addRailKey(parent: LinearLayout, label: String, action: () -> Unit) {
        val view = actionKey(label, action)
        val params = LinearLayout.LayoutParams(-1, 0, 1f)
        parent.addView(view, params)
    }

    private fun keyView(key: KeySpec): View {
        if (key.edgeAlias) {
            return edgeAliasKey(key)
        }
        if (key.special == Special.DIRECTION_PAD) {
            return directionPadKey(key)
        }
        val shortcutActions = launcherShortcuts?.forKey(key.text).orEmpty()
        if (
            key.longText == null &&
            key.longKeyCode == null &&
            key.longSpecial == null &&
            key.accentVariants.isEmpty() &&
            shortcutActions.isEmpty() &&
            !canGlideFromKey(key)
        ) {
            return actionKey(
                key.label,
                action = { handleKey(key) },
                repeatAction = when (key.special) {
                    Special.BACKSPACE -> { { backspace() } }
                    Special.FORWARD_DELETE -> { { forwardDelete() } }
                    else -> null
                },
                active = isSpecialKeyActive(key.special),
                specialStyle = key.specialStyle,
                previewLabel = tapPreviewLabel(key)
            )
        }

        val view = FrameLayout(this)
        view.background = keyBackground(active = false)
        bindLongPressKey(view, key)
        registerGlideKey(view, key)

        view.addView(longPressLabelGroup(key), FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))

        val longDescriptions = buildList {
            key.longLabel?.takeIf { it.isNotBlank() }?.let(::add)
            shortcutActions.mapTo(this) { it.label }
        }
        view.contentDescription = if (longDescriptions.isEmpty()) {
            key.label
        } else {
            "${key.label}, long press for ${longDescriptions.joinToString()}"
        }
        return view
    }

    private fun directionPadKey(key: KeySpec): View {
        val iconColor = if (key.specialStyle) theme.specialKeyText else theme.keyText
        val view = DirectionPadIconView(this, iconColor, resources.displayMetrics.density)
        view.background = if (key.specialStyle) specialKeyBackground(active = false) else keyBackground(active = false)
        bindDirectionPadKey(view)
        view.contentDescription = "Direction pad"
        return view
    }

    private fun canGlideFromKey(key: KeySpec): Boolean {
        val text = key.text ?: return false
        return layout.glideTyping &&
            activeLanguagePack == null &&
            !isLandscape() &&
            !symbols &&
            !usesNumberPad() &&
            !hasLatchedModifiers() &&
            key.special == null &&
            text.length == 1 &&
            text[0].isLetter()
    }

    private fun registerGlideKey(view: View, key: KeySpec) {
        if (!canGlideFromKey(key)) return
        val char = key.text?.singleOrNull()?.lowercaseChar() ?: return
        view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            val bounds = Rect(
                location[0],
                location[1],
                location[0] + target.width,
                location[1] + target.height
            )
            val existing = glideKeyHits.indexOfFirst { it.view == target }
            val hit = GlideKeyHit(target, char, bounds)
            if (existing >= 0) {
                glideKeyHits[existing] = hit
            } else {
                glideKeyHits.add(hit)
            }
        }
    }

    private fun longPressLabelGroup(key: KeySpec): LinearLayout {
        val group = LinearLayout(this)
        group.orientation = LinearLayout.HORIZONTAL
        group.gravity = Gravity.CENTER
        group.isBaselineAligned = false

        val primary = keyLabel(key.label, Gravity.CENTER, keyTextSize(key.label))
        group.addView(primary, LinearLayout.LayoutParams(-2, -2))

        val longLabel = key.longLabel
        if (!longLabel.isNullOrBlank()) {
            val hint = keyLabel(longLabel, Gravity.CENTER, altKeyTextSize(key.label, longLabel))
            hint.alpha = 0.82f
            hint.translationY = -dp(6).toFloat()
            val hintParams = LinearLayout.LayoutParams(-2, -2)
            hintParams.leftMargin = dp(1)
            group.addView(hint, hintParams)
        }

        return group
    }

    private fun edgeAliasKey(key: KeySpec): View {
        val view = View(this)
        view.background = ColorDrawable(Color.TRANSPARENT)
        view.contentDescription = key.text ?: key.label
        bindImmediateKey(view, action = { handleKey(key) })
        return view
    }

    private fun isSpecialKeyActive(special: Special?): Boolean {
        return when (special) {
            Special.SHIFT -> capsLocked
            Special.CTRL -> ctrlLatched
            Special.ALT -> altLatched
            Special.SUPER -> metaLatched
            else -> false
        }
    }

    private fun actionKey(
        label: String,
        action: () -> Unit,
        repeatAction: (() -> Unit)? = null,
        active: Boolean = false,
        specialStyle: Boolean = false,
        previewLabel: String? = null
    ): TextView {
        val view = keyLabel(label, Gravity.CENTER, keyTextSize(label))
        view.setTextColor(if (specialStyle) theme.specialKeyText else theme.keyText)
        view.background = if (specialStyle) specialKeyBackground(active) else keyBackground(active)
        bindImmediateKey(view, action, repeatAction, previewLabel = previewLabel)
        return view
    }

    private fun bindImmediateKey(
        view: View,
        action: () -> Unit,
        repeatAction: (() -> Unit)? = null,
        dismissEmojiOnDown: Boolean = true,
        previewLabel: String? = null
    ) {
        view.isClickable = true
        view.isFocusable = false
        var popup: PopupWindow? = null
        fun clearPopup() {
            popup?.dismiss()
            popup = null
        }
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    clearPopup()
                    popup = showKeyPreview(touched, previewLabel)
                    touched.isPressed = true
                    pressFeedback(touched)
                    if (dismissEmojiOnDown && emojiMode) closeEmojiMode()
                    if (dismissEmojiOnDown && clipboardMode) closeClipboardMode()
                    action()
                    repeatAction?.let { startRepeat(it) }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    clearPopup()
                    touched.isPressed = false
                    stopRepeat()
                    true
                }
                else -> true
            }
        }
    }

    private fun bindTapKey(view: View, action: () -> Unit) {
        view.isClickable = true
        view.isFocusable = false
        view.setOnClickListener { clicked ->
            pressFeedback(clicked)
            action()
        }
    }

    private fun bindDirectionPadKey(view: View) {
        view.isClickable = true
        view.isFocusable = false
        var downRawX = 0f
        var downRawY = 0f
        var activeKeyCode: Int? = null
        var popup: DirectionPadPopup? = null

        fun move(keyCode: Int) {
            popup?.select(keyCode)
            if (activeKeyCode == keyCode) return
            stopRepeat()
            activeKeyCode = keyCode
            sendDirectionKeyCode(keyCode)
            startDirectionRepeat(keyCode)
        }

        fun clearSelection() {
            if (activeKeyCode == null) return
            activeKeyCode = null
            stopRepeat()
            popup?.select(null)
        }

        fun closePopup() {
            popup?.popup?.dismiss()
            popup = null
        }

        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (emojiMode) closeEmojiMode()
                    if (clipboardMode) closeClipboardMode()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    activeKeyCode = null
                    closePopup()
                    popup = showDirectionPadPopup(touched)
                    touched.isPressed = true
                    pressFeedback(touched)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val keyCode = directionKeyCodeForSwipe(downRawX, downRawY, event.rawX, event.rawY)
                    if (keyCode == null) {
                        clearSelection()
                    } else {
                        move(keyCode)
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    touched.isPressed = false
                    clearSelection()
                    closePopup()
                    true
                }
                else -> true
            }
        }
    }

    private fun moveCursorWithinActiveInput(keyCode: Int): Boolean {
        val ic = currentInputConnection ?: return false
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val text = extracted.text?.toString() ?: return false
        if (text.isEmpty()) return false
        val selectionStart = extracted.selectionStart.coerceIn(0, text.length)
        val selectionEnd = extracted.selectionEnd.coerceIn(0, text.length)
        val cursor = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> min(selectionStart, selectionEnd)
            KeyEvent.KEYCODE_DPAD_RIGHT -> max(selectionStart, selectionEnd)
            else -> selectionEnd
        }
        val next = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> (cursor - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> (cursor + 1).coerceAtMost(text.length)
            KeyEvent.KEYCODE_DPAD_UP -> verticalCursorTarget(text, cursor, up = true)
            KeyEvent.KEYCODE_DPAD_DOWN -> verticalCursorTarget(text, cursor, up = false)
            else -> return false
        }
        if (next == cursor) return false
        val absoluteNext = (extracted.startOffset + next).coerceAtLeast(0)
        return ic.setSelection(absoluteNext, absoluteNext)
    }

    private fun verticalCursorTarget(text: String, cursor: Int, up: Boolean): Int {
        val safeCursor = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (safeCursor - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', safeCursor).let { if (it < 0) text.length else it }
        val column = safeCursor - lineStart
        return if (up) {
            if (lineStart == 0) return safeCursor
            val previousEnd = lineStart - 1
            val previousStart = text.lastIndexOf('\n', (previousEnd - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            (previousStart + column).coerceAtMost(previousEnd)
        } else {
            if (lineEnd >= text.length) return safeCursor
            val nextStart = lineEnd + 1
            val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
            (nextStart + column).coerceAtMost(nextEnd)
        }
    }

    private fun bindLongPressKey(view: FrameLayout, key: KeySpec) {
        view.isClickable = true
        view.isFocusable = false
        val longPressChoices = longPressChoices(key)
        var longPressHandled = false
        var primaryCommitted = false
        var longPressRunnable: Runnable? = null
        var popup: PopupWindow? = null
        var choicePopup: LongPressPopup? = null
        var choiceIndex = 0
        var shortcutSelectionArmed = false
        var downRawX = 0f
        var downRawY = 0f
        var glideTracking = false
        val glideTrace = mutableListOf<Char>()
        val glidePoints = mutableListOf<GlidePoint>()
        fun clearPopup() {
            popup?.dismiss()
            popup = null
            choicePopup?.popup?.dismiss()
            choicePopup = null
        }
        fun updateLongPressSelection(rawX: Float) {
            val current = choicePopup ?: return
            val nextIndex = current.indexForRawX(rawX)
            if (nextIndex == choiceIndex) return
            choiceIndex = nextIndex
            current.select(nextIndex)
            if (layout.vibrateOnKeypress) {
                vibrateKey(view, HapticFeedbackConstants.KEYBOARD_TAP, durationMs = 6L)
            }
        }
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressHandled = false
                    primaryCommitted = false
                    choiceIndex = 0
                    shortcutSelectionArmed = false
                    glideTracking = false
                    glideTrace.clear()
                    glidePoints.clear()
                    clearGlideTrail()
                    clearPopup()
                    popup = showKeyPreview(touched, tapPreviewLabel(key))
                    downRawX = event.rawX
                    downRawY = event.rawY
                    touched.isPressed = true
                    pressFeedback(touched)
                    if (canCommitPrimaryOnDown(key)) {
                        handleKey(key)
                        primaryCommitted = true
                    }
                    longPressRunnable = Runnable {
                        longPressHandled = true
                        if (primaryCommitted) rollbackPrimaryCommit(key)
                        clearPopup()
                        if (longPressChoices.isNotEmpty()) {
                            choiceIndex = 0
                            choicePopup = showLongPressPicker(view, longPressChoices)
                            updateLongPressSelection(downRawX)
                        } else if (key.longSpecial == Special.EMOJI_PICKER) {
                            openEmojiMode()
                        } else {
                            popup = showLongPressPreview(view, key)
                            handleLongKey(key)
                        }
                        if (layout.vibrateOnKeypress) {
                            vibrateKey(touched, HapticFeedbackConstants.LONG_PRESS, durationMs = 18L)
                        }
                    }
                    repeatHandler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longPressHandled && canGlideFromKey(key)) {
                        if (!glideTracking && movedPastGlideThreshold(event.rawX, event.rawY, downRawX, downRawY)) {
                            longPressRunnable?.let { repeatHandler.removeCallbacks(it) }
                            longPressRunnable = null
                            clearPopup()
                            glideTracking = true
                            touched.isPressed = false
                            appendGlideHit(glideTrace, downRawX, downRawY)
                            appendGlidePoint(glidePoints, downRawX, downRawY)
                            beginGlideTrail(downRawX, downRawY)
                            if (layout.vibrateOnKeypress) {
                                vibrateKey(touched, HapticFeedbackConstants.KEYBOARD_TAP, durationMs = 6L)
                            }
                        }
                        if (glideTracking) {
                            appendGlideHit(glideTrace, event.rawX, event.rawY)
                            appendGlidePoint(glidePoints, event.rawX, event.rawY)
                            extendGlideTrail(event.rawX, event.rawY)
                            return@setOnTouchListener true
                        }
                    }
                    if (longPressHandled && choicePopup != null) {
                        updateLongPressSelection(event.rawX)
                        if (
                            longPressChoices.getOrNull(choiceIndex)?.shortcut != null &&
                            movedPastSelectionThreshold(event.rawX, event.rawY, downRawX, downRawY)
                        ) {
                            shortcutSelectionArmed = true
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { repeatHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    if (glideTracking) {
                        appendGlideHit(glideTrace, event.rawX, event.rawY)
                        appendGlidePoint(glidePoints, event.rawX, event.rawY)
                        extendGlideTrail(event.rawX, event.rawY)
                        touched.isPressed = false
                        commitGlideTrace(glideTrace, glidePoints)
                        finishGlideTrail()
                        glideTracking = false
                        glideTrace.clear()
                        glidePoints.clear()
                        true
                    } else {
                        val selectedChoice = if (longPressHandled && choicePopup != null) {
                            longPressChoices.getOrNull(choiceIndex)
                        } else {
                            null
                        }
                        touched.isPressed = false
                        if (selectedChoice?.text != null) {
                            clearPopup()
                            commitFromKey(selectedChoice.text)
                        } else if (selectedChoice?.shortcut != null && shortcutSelectionArmed) {
                            clearPopup()
                            requestLauncherShortcut(selectedChoice.shortcut)
                        } else if (longPressHandled) {
                            // Non-picker long-press actions already fire at timeout; shortcut picks require a swipe.
                            clearPopup()
                        } else if (!primaryCommitted) {
                            clearPopup()
                            handleKey(key)
                        } else {
                            clearPopup()
                            refreshSuggestionStripSoon()
                        }
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { repeatHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    clearPopup()
                    glideTracking = false
                    glideTrace.clear()
                    glidePoints.clear()
                    clearGlideTrail()
                    touched.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun longPressChoices(key: KeySpec): List<LongPressChoice> = buildList {
        key.accentVariants.mapTo(this) { LongPressChoice(text = it) }
        launcherShortcuts?.forKey(key.text).orEmpty().mapTo(this) { LongPressChoice(shortcut = it) }
    }

    private fun movedPastSelectionThreshold(
        rawX: Float,
        rawY: Float,
        downRawX: Float,
        downRawY: Float
    ): Boolean {
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        val threshold = dpFloat(12f)
        return (dx * dx) + (dy * dy) >= threshold * threshold
    }

    private fun movedPastGlideThreshold(rawX: Float, rawY: Float, downRawX: Float, downRawY: Float): Boolean {
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        val threshold = dpFloat(18f)
        return (dx * dx) + (dy * dy) >= threshold * threshold
    }

    private fun directionKeyCodeForSwipe(downRawX: Float, downRawY: Float, rawX: Float, rawY: Float): Int? {
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        val threshold = dpFloat(12f)
        if ((dx * dx) + (dy * dy) < threshold * threshold) return null
        return if (abs(dx) >= abs(dy)) {
            if (dx < 0f) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            if (dy < 0f) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        }
    }

    private fun showDirectionPadPopup(anchor: View): DirectionPadPopup {
        val cellSize = dp(44)
        val gapSize = dp(18)
        val sideGapSize = (cellSize + gapSize) / 2
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(0, 0, 0, 0)

        val cells = mutableMapOf<Int, TextView>()
        fun arrowCell(label: String, keyCode: Int): TextView {
            val cell = keyLabel(label, Gravity.CENTER, keyTextSize(label))
            cell.setTextColor(theme.specialKeyText)
            cell.background = keyBackground(active = false)
            cells[keyCode] = cell
            return cell
        }
        fun spacer(width: Int, height: Int): View {
            return View(this).apply {
                background = ColorDrawable(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(width, height)
            }
        }
        fun addRow(left: View, center: View, right: View, height: Int) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(left)
            row.addView(center)
            row.addView(right)
            grid.addView(row, LinearLayout.LayoutParams(-2, height))
        }

        addRow(
            spacer(sideGapSize, cellSize),
            arrowCell(ICON_UP, KeyEvent.KEYCODE_DPAD_UP).apply {
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            },
            spacer(sideGapSize, cellSize),
            cellSize
        )
        addRow(
            arrowCell(ICON_LEFT, KeyEvent.KEYCODE_DPAD_LEFT).apply {
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            },
            spacer(gapSize, cellSize),
            arrowCell(ICON_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT).apply {
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            },
            cellSize
        )
        addRow(
            spacer(sideGapSize, cellSize),
            arrowCell(ICON_DOWN, KeyEvent.KEYCODE_DPAD_DOWN).apply {
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            },
            spacer(sideGapSize, cellSize),
            cellSize
        )

        val popup = PopupWindow(grid, cellSize * 2 + gapSize, cellSize * 3, false)
        popup.isOutsideTouchable = false
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = location[0] + (anchor.width / 2) - (popup.width / 2)
        val y = location[1] + (anchor.height / 2) - (popup.height / 2)
        popup.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y)
        return DirectionPadPopup(
            popup = popup,
            cells = cells,
            inactiveBackground = keyBackground(active = false),
            activeBackground = specialKeyBackground(active = true)
        )
    }

    private fun appendGlideHit(trace: MutableList<Char>, rawX: Float, rawY: Float) {
        val hit = glideKeyAt(rawX.toInt(), rawY.toInt()) ?: return
        if (trace.isEmpty() || trace.last() != hit.char) {
            trace.add(hit.char)
        }
    }

    private fun appendGlidePoint(points: MutableList<GlidePoint>, rawX: Float, rawY: Float) {
        val next = GlidePoint(rawX, rawY)
        val last = points.lastOrNull()
        if (last == null) {
            points.add(next)
            return
        }
        val dx = next.x - last.x
        val dy = next.y - last.y
        val minDistance = dpFloat(4f)
        if ((dx * dx) + (dy * dy) >= minDistance * minDistance) {
            points.add(next)
        }
    }

    private fun glideKeyAt(rawX: Int, rawY: Int): GlideKeyHit? {
        glideKeyHits.firstOrNull { it.bounds.contains(rawX, rawY) }?.let { return it }
        return glideKeyHits
            .map { hit ->
                val centerX = hit.bounds.centerX()
                val centerY = hit.bounds.centerY()
                val dx = rawX - centerX
                val dy = rawY - centerY
                hit to ((dx * dx) + (dy * dy))
            }
            .filter { (_, distanceSquared) -> distanceSquared <= dp(44) * dp(44) }
            .minByOrNull { (_, distanceSquared) -> distanceSquared }
            ?.first
    }

    private fun glideKeyCenters(): Map<Char, GlidePoint> {
        return glideKeyHits.associate { hit ->
            hit.char to GlidePoint(hit.bounds.centerX().toFloat(), hit.bounds.centerY().toFloat())
        }
    }

    private fun commitGlideTrace(trace: List<Char>, points: List<GlidePoint>) {
        if (trace.size < 2) {
            refreshSuggestionStripSoon()
            return
        }
        val rawTrace = trace.joinToString(separator = "")
        val keyCenters = glideKeyCenters()
        val contextWords = previousWordsBeforeCursor(3)
        val geometryCandidates = LocalDictionary.suggestGlideGeometry(
            prefs = prefs,
            points = points,
            keyCenters = keyCenters,
            rawTrace = rawTrace,
            limit = 5,
            previousWords = contextWords
        )
        val traceCandidates = if (rawTrace.length <= 5) {
            LocalDictionary.suggestGlide(prefs, rawTrace, 5)
        } else {
            emptyList()
        }
        val geometrySuggestion = geometryCandidates.firstOrNull()
        val suggestion = geometrySuggestion ?: traceCandidates.firstOrNull()
        val fallbackTrace = if (rawTrace.length == 3) {
            "${rawTrace.first()}${rawTrace.last()}"
        } else {
            rawTrace
        }
        val commitValue = suggestion
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?: fallbackTrace.takeIf { it.length in 2..3 }
        if (commitValue.isNullOrBlank()) {
            recordGlideDiagnostics(
                rawTrace = rawTrace,
                points = points,
                keyCenters = keyCenters,
                geometryCandidates = geometryCandidates,
                traceCandidates = traceCandidates,
                contextWords = contextWords,
                committed = null,
                source = "none"
            )
            refreshSuggestionStripSoon()
            return
        }
        val casedCommitValue = applyActiveWordCasing(commitValue)
        currentInputConnection?.commitText("$casedCommitValue ", 1)
        localWordBeforeCursor = ""
        pendingAddWord = null
        if (!suggestion.isNullOrBlank()) {
            LocalDictionary.recordAcceptedWord(prefs, suggestion)
        }
        recordGlideDiagnostics(
            rawTrace = rawTrace,
            points = points,
            keyCenters = keyCenters,
            geometryCandidates = geometryCandidates,
            traceCandidates = traceCandidates,
            contextWords = contextWords,
            committed = casedCommitValue,
            source = when {
                geometrySuggestion != null -> "geometry"
                suggestion != null -> "trace"
                else -> "fallback"
            }
        )
        if (shifted && !capsLocked) {
            shifted = false
            lastShiftTapAtMs = 0L
            setInputView(buildKeyboardView())
        } else {
            refreshSuggestionStripSoon()
        }
    }

    private fun recordGlideDiagnostics(
        rawTrace: String,
        points: List<GlidePoint>,
        keyCenters: Map<Char, GlidePoint>,
        geometryCandidates: List<String>,
        traceCandidates: List<String>,
        contextWords: List<String>,
        committed: String?,
        source: String
    ) {
        if (!layout.glideDiagnostics) return
        try {
            val file = glideDiagnosticsFile()
            rotateGlideDiagnostics(file)
            val payload = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("rawTrace", rawTrace)
                .put("committed", committed ?: JSONObject.NULL)
                .put("source", source)
                .put("pointCount", points.size)
                .put("geometryCandidates", JSONArray(geometryCandidates))
                .put("traceCandidates", JSONArray(traceCandidates))
                .put("contextWords", JSONArray(contextWords))
                .put("points", glidePointArray(points))
                .put("keyCenters", glideKeyCenterObject(keyCenters))
            file.appendText(payload.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    private fun glideDiagnosticsFile(): File {
        val root = getExternalFilesDir(null) ?: filesDir
        val dir = File(root, "glide-diagnostics")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "glide-sessions.jsonl")
    }

    private fun rotateGlideDiagnostics(file: File) {
        val maxBytes = 512 * 1024
        if (file.exists() && file.length() > maxBytes) {
            val previous = File(file.parentFile, "glide-sessions.previous.jsonl")
            if (previous.exists()) previous.delete()
            file.renameTo(previous)
        }
    }

    private fun glidePointArray(points: List<GlidePoint>): JSONArray {
        val out = JSONArray()
        points.take(160).forEach { point ->
            out.put(
                JSONObject()
                    .put("x", point.x.roundToInt())
                    .put("y", point.y.roundToInt())
            )
        }
        return out
    }

    private fun glideKeyCenterObject(keyCenters: Map<Char, GlidePoint>): JSONObject {
        val out = JSONObject()
        keyCenters.toSortedMap().forEach { (char, point) ->
            out.put(
                char.toString(),
                JSONObject()
                    .put("x", point.x.roundToInt())
                    .put("y", point.y.roundToInt())
            )
        }
        return out
    }

    private fun beginGlideTrail(rawX: Float, rawY: Float) {
        glideTrailView?.beginRaw(rawX, rawY)
    }

    private fun extendGlideTrail(rawX: Float, rawY: Float) {
        glideTrailView?.extendRaw(rawX, rawY)
    }

    private fun finishGlideTrail() {
        val trail = glideTrailView ?: return
        repeatHandler.postDelayed({ trail.clear() }, GLIDE_TRAIL_HOLD_MS)
    }

    private fun clearGlideTrail() {
        glideTrailView?.clear()
    }

    private fun canCommitPrimaryOnDown(key: KeySpec): Boolean {
        if (canGlideFromKey(key)) return false
        return key.longSpecial == null &&
            key.text != null &&
            key.special == null &&
            !isShiftActive()
    }

    private fun rollbackPrimaryCommit(key: KeySpec) {
        val text = key.text ?: return
        currentInputConnection?.deleteSurroundingText(text.length, 0)
    }

    private fun tapPreviewLabel(key: KeySpec): String? {
        val text = key.text ?: return null
        return text.takeIf { it.length == 1 && it.isNotBlank() }
    }

    private fun showLongPressPreview(anchor: View, key: KeySpec): PopupWindow? {
        val label = key.longLabel ?: key.longText ?: return null
        val description = when (key.longSpecial) {
            Special.SETTINGS -> "Open settings"
            Special.SHIFT_ENTER -> "Send Shift+Enter"
            else -> "Insert $label"
        }
        return showKeyPreview(anchor, label, description)
    }

    private fun showKeyPreview(anchor: View, label: String?, description: String? = null): PopupWindow? {
        if (label.isNullOrBlank()) return null
        dismissActiveKeyPreview()
        val preview = keyLabel(label, Gravity.CENTER, max(14, keyTextSize(label) + 4))
        preview.setTextColor(theme.keyText)
        preview.background = panel(brightenColor(theme.keyBg, 1.22f, 30), theme.border, 4)
        preview.elevation = dpFloat(8f)
        preview.contentDescription = description ?: "Key preview $label"

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
            activeKeyPreview = popup
            popup.setOnDismissListener {
                if (activeKeyPreview === popup) activeKeyPreview = null
            }
            repeatHandler.postDelayed({
                if (activeKeyPreview === popup) popup.dismiss()
            }, KEY_PREVIEW_TIMEOUT_MS)
            popup
        } catch (_: Exception) {
            null
        }
    }

    private fun showLongPressPicker(anchor: View, choices: List<LongPressChoice>): LongPressPopup? {
        if (choices.isEmpty()) return null
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = panel(brightenColor(theme.keyBg, 1.22f, 30), theme.border, 4)
        row.elevation = dpFloat(8f)

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val availableWidth = max(anchor.rootView.width, resources.displayMetrics.widthPixels)
        val cellWidth = min(dp(40), availableWidth / choices.size)
        val height = dp(44)
        val cells = choices.map { choice ->
            val cell = choice.text?.let { text ->
                keyLabel(text, Gravity.CENTER, max(14, keyTextSize(text) + 4)).apply {
                    contentDescription = "Insert $text"
                }
            } ?: ImageView(this).apply {
                contentDescription = choice.shortcut?.label
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(7), dp(7), dp(7), dp(7))
                val icon = choice.shortcut?.iconUri?.let { uri ->
                    runCatching {
                        setImageURI(Uri.parse(uri))
                        drawable
                    }.getOrNull()
                }
                if (icon == null) {
                    setImageDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(theme.keyText)
                        setSize(dp(12), dp(12))
                    })
                }
            }
            row.addView(cell, LinearLayout.LayoutParams(cellWidth, -1))
            cell
        }

        val width = cellWidth * choices.size
        val popup = PopupWindow(row, width, height, false)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = false
        popup.isClippingEnabled = false
        popup.elevation = dpFloat(8f)

        val desiredLeft = anchorLocation[0] + (anchor.width - width) / 2
        val popupLeft = desiredLeft.coerceIn(0, max(0, availableWidth - width))
        val xOffset = popupLeft - anchorLocation[0]
        val yOffset = -anchor.height - height - dp(18)
        val activeBackground = panel(brightenColor(theme.keyBg, 1.42f, 70), theme.border, 4)
        return try {
            popup.showAsDropDown(anchor, xOffset, yOffset)
            LongPressPopup(popup, row, cells, cellWidth, activeBackground).also { it.select(0) }
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
        view.typeface = keyboardTypeface()
        view.textSize = sizeSp.toFloat()
        view.setTextColor(theme.keyText)
        return view
    }

    private fun keyboardTypeface(): Typeface {
        val uri = layout.fontUri
        if (uri == cachedFontUri) return cachedTypeface ?: Typeface.MONOSPACE
        cachedFontUri = uri
        cachedTypeface = uri?.let {
            try {
                contentResolver.openFileDescriptor(Uri.parse(it), "r")?.use { descriptor ->
                    Typeface.Builder(descriptor.fileDescriptor).build()
                }
            } catch (_: Exception) {
                null
            }
        }
        return cachedTypeface ?: Typeface.MONOSPACE
    }

    private fun dismissActiveKeyPreview() {
        activeKeyPreview?.dismiss()
        activeKeyPreview = null
    }

    private fun keyTextSize(label: String): Int {
        val baseSize = layout.characterSizeSp.coerceIn(10, 24)
        return when {
            label.length > 5 -> max(9, baseSize - 5)
            label.length >= 4 -> max(10, baseSize - 4)
            label.length >= 3 -> max(10, baseSize - 2)
            else -> baseSize
        }
    }

    private fun altKeyTextSize(primaryLabel: String, altLabel: String): Int {
        val primarySize = keyTextSize(primaryLabel)
        val scaled = (primarySize * 0.72f).roundToInt()
        val adjusted = if (altLabel.length > 1) scaled - 1 else scaled
        return adjusted.coerceIn(8, max(8, primarySize - 1))
    }

    private fun usesNumberPad(): Boolean {
        return usesNumberPad(currentInfo)
    }

    private fun usesNumberPad(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
    }

    private fun shouldOfferSuggestions(): Boolean {
        return shouldOfferSuggestions(layout, currentInfo)
    }

    private fun shouldOfferSuggestions(settings: KeyboardLayoutSettings, info: EditorInfo?): Boolean {
        return settings.localSuggestions && !usesNumberPad(info) && (info == null || !isPasswordField(info))
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
        return when (currentImeAction()) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT -> ICON_ENTER_GO
            EditorInfo.IME_ACTION_SEARCH -> ICON_SEARCH
            EditorInfo.IME_ACTION_SEND -> ICON_SEND
            EditorInfo.IME_ACTION_DONE -> ICON_DONE
            else -> ICON_ENTER
        }
    }

    private fun enterUsesEditorAction(): Boolean {
        val action = currentImeAction()
        return action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
    }

    private fun currentImeAction(): Int {
        return (currentInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
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
        val handled = view.performHapticFeedback(feedbackConstant)
        if (handled) return

        val vibrator = systemVibrator() ?: return

        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrateLegacy(vibrator, durationMs)
        }
    }

    private fun systemVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            legacySystemVibrator()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacySystemVibrator(): Vibrator? = getSystemService(VIBRATOR_SERVICE) as? Vibrator

    @Suppress("DEPRECATION")
    private fun vibrateLegacy(vibrator: Vibrator, durationMs: Long) {
        vibrator.vibrate(durationMs)
    }

    private fun keyboardRoot(orientation: Int): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = orientation
        val sidePadding = dp(layout.horizontalMarginDp)
        val bottomPadding = dp(layout.bottomMarginDp)
        applyKeyboardRootPadding(root, sidePadding, bottomPadding, initialBottomSafeInsetPx())
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottomInset = systemBottomInset(insets)
            lastBottomSafeInsetPx = bottomInset
            applyKeyboardRootPadding(view, sidePadding, bottomPadding, bottomInset)
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

    private fun applyKeyboardRootPadding(view: View, sidePadding: Int, bottomPadding: Int, bottomInset: Int) {
        view.setPadding(sidePadding, 0, sidePadding, max(0, bottomPadding + bottomInset))
    }

    private fun initialBottomSafeInsetPx(): Int {
        return max(lastBottomSafeInsetPx, gestureNavigationFallbackBottomInsetPx())
    }

    private fun systemBottomInset(insets: WindowInsets): Int {
        val inset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            val gestures = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
            val mandatoryGestures = insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
            max(bars, max(gestures, mandatoryGestures))
        } else {
            legacySystemWindowInsetBottom(insets)
        }
        return max(inset, gestureNavigationFallbackBottomInsetPx())
    }

    @Suppress("DEPRECATION")
    private fun legacySystemWindowInsetBottom(insets: WindowInsets): Int = insets.systemWindowInsetBottom

    private fun gestureNavigationFallbackBottomInsetPx(): Int {
        return if (isGestureNavigationMode()) dp(24) else 0
    }

    private fun isGestureNavigationMode(): Boolean {
        val secureMode = secureNavigationMode()
        if (secureMode >= 0) return secureMode == NAV_MODE_GESTURAL
        val resourceMode = frameworkNavigationMode()
        return resourceMode == NAV_MODE_GESTURAL
    }

    private fun secureNavigationMode(): Int {
        return try {
            Settings.Secure.getInt(contentResolver, "navigation_mode")
        } catch (_: Exception) {
            -1
        }
    }

    private fun frameworkNavigationMode(): Int {
        return try {
            val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            if (id == 0) -1 else resources.getInteger(id)
        } catch (_: Exception) {
            -1
        }
    }

    private fun keyboardViewSignature(nextLayout: KeyboardLayoutSettings): KeyboardViewSignature {
        val info = currentInfo
        return KeyboardViewSignature(
            orientation = resources.configuration.orientation,
            layout = nextLayout,
            theme = theme,
            inputType = info?.inputType ?: 0,
            imeAction = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION,
            usesNumberPad = usesNumberPad(info),
            offersSuggestions = shouldOfferSuggestions(nextLayout, info),
            symbols = symbols,
            emojiMode = emojiMode,
            clipboardMode = clipboardMode,
            clipboardClearPending = clipboardClearPending,
            languageId = activeLanguagePack?.id ?: LanguagePackManager.ENGLISH_ID,
            emojiCategoryIndex = emojiCategoryIndex
        )
    }

    private fun keyboardBackground(): Drawable? {
        val bitmap = backgroundBitmap() ?: return ColorDrawable(theme.bg)
        return KeyboardBackgroundDrawable(theme.bg, bitmap, layout.backgroundImageOpacity)
    }

    private fun backgroundBitmap(): Bitmap? {
        val uri = layout.backgroundImageUri
        if (uri.isNullOrBlank()) {
            cachedBackgroundUri = null
            cachedBackgroundBitmap = null
            return null
        }
        val cached = cachedBackgroundBitmap
        if (uri == cachedBackgroundUri && cached != null && !cached.isRecycled) {
            return cached
        }

        return try {
            val decoded = decodeSampledBackgroundBitmap(Uri.parse(uri)) ?: return null
            cachedBackgroundUri = uri
            cachedBackgroundBitmap = decoded
            decoded
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledBackgroundBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val targetHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = backgroundSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun backgroundSampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var sampleSize = 1
        while (
            sourceWidth / (sampleSize * 2) >= targetWidth ||
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun rowParams(heightDp: Int): LinearLayout.LayoutParams {
        val heightPercent = if (isLandscape()) {
            layout.landscapeHeightPercent
        } else {
            layout.portraitHeightPercent
        }
        val minHeight = if (isLandscape()) 24 else 28
        val scaledHeight = max(minHeight, (heightDp * heightPercent / 100f).roundToInt())
        val params = LinearLayout.LayoutParams(-1, dp(scaledHeight))
        params.setMargins(0, 0, 0, dp(layout.keyGapDp))
        return params
    }

    private fun weightedRowParams(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(-1, 0, 1f)
        params.setMargins(0, 0, 0, dp(layout.keyGapDp))
        return params
    }

    private fun fixedRowParams(heightDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(-1, dp(heightDp))
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
        return keyVisualInset(keyStateBackground(theme.keyBg, theme.border, active))
    }

    private fun specialKeyBackground(active: Boolean): Drawable {
        val fill = specialKeyFillColor(active)
        return keyVisualInset(keyStateBackground(fill, theme.border, active))
    }

    private fun keyStateBackground(fill: Int, stroke: Int, active: Boolean): Drawable {
        val normalFill = if (active) brightenColor(fill, 1.16f, 36) else fill
        val pressedFill = brightenColor(normalFill, 1.22f, 42)
        val fallback = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), panel(pressedFill, stroke, 5))
            addState(intArrayOf(), panel(normalFill, stroke, 5))
        }
        return frameRenderer.drawable(
            RetuiVisualContract.FRAME_ROLE_KEYBOARD,
            normalFill,
            fallback,
            pressedFill
        )
    }

    private fun keyVisualInset(drawable: Drawable): Drawable {
        val inset = dp(layout.keyGapDp)
        return if (inset <= 0) drawable else InsetDrawable(drawable, inset, inset, inset, inset)
    }

    private fun specialKeyFillColor(active: Boolean): Int {
        val base = if (theme.specialKeyBg != 0) theme.specialKeyBg else theme.border
        if (!active) return base
        return brightenColor(base, 1.14f, 32)
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
            Special.FORWARD_DELETE -> forwardDelete()
            Special.ENTER -> {
                if (hasLatchedModifiers()) sendKeyCode(KeyEvent.KEYCODE_ENTER) else enter()
            }
            Special.SHIFT -> handleShift()
            Special.SPACE -> {
                if (hasLatchedModifiers()) sendKeyCode(KeyEvent.KEYCODE_SPACE) else handleSpace()
            }
            Special.SYMBOLS -> {
                symbols = !symbols
                shifted = false
                capsLocked = false
                clearLatchedModifiers()
                setInputView(buildKeyboardView())
            }
            Special.LANGUAGE -> switchLanguage()
            Special.CTRL -> toggleModifier(Special.CTRL)
            Special.ALT -> toggleModifier(Special.ALT)
            Special.SUPER -> toggleModifier(Special.SUPER)
            Special.DIRECTION_PAD -> Unit
            Special.SHIFT_ENTER -> sendShiftEnter()
            Special.EMOJI_PICKER -> openEmojiMode()
            Special.SETTINGS -> openKeyboardSettings()
            Special.HIDE -> requestHideSelf(0)
            Special.SPACER, null -> {
                when {
                    key.keyCode != null -> sendKeyCode(key.keyCode)
                    key.text != null -> {
                        if (!sendTextWithLatchedModifiers(key.text)) {
                            commitFromKey(key.text)
                        }
                    }
                }
            }
        }
    }

    private fun handleLongKey(key: KeySpec) {
        when {
            key.longSpecial == Special.SETTINGS -> openKeyboardSettings()
            key.longSpecial == Special.SHIFT_ENTER -> sendShiftEnter()
            key.longSpecial == Special.EMOJI_PICKER -> openEmojiMode()
            key.longKeyCode != null -> sendKeyCode(key.longKeyCode)
            key.longText != null -> commitFromKey(key.longText)
        }
    }

    private fun openEmojiMode() {
        stopRepeat()
        cancelSuggestionRefresh()
        symbols = false
        shifted = false
        capsLocked = false
        clearLatchedModifiers()
        emojiMode = true
        clipboardMode = false
        emojiCategoryIndex = emojiCategoryIndex.coerceIn(0, emojiCategoryCount() - 1)
        setInputView(buildKeyboardView())
    }

    private fun closeEmojiMode() {
        if (!emojiMode) return
        emojiMode = false
        setInputView(buildKeyboardView())
        refreshSuggestionStripSoon()
    }

    private fun selectEmojiCategory(index: Int) {
        val safeIndex = index.coerceIn(0, emojiCategoryCount() - 1)
        if (safeIndex == emojiCategoryIndex) return
        emojiCategoryIndex = safeIndex
        setInputView(buildKeyboardView())
    }

    private fun openClipboardMode() {
        stopRepeat()
        cancelSuggestionRefresh()
        symbols = false
        shifted = false
        capsLocked = false
        clearLatchedModifiers()
        emojiMode = false
        clipboardMode = true
        clipboardClearPending = false
        captureClipboardIfEnabled()
        setInputView(buildKeyboardView())
    }

    private fun closeClipboardMode() {
        if (!clipboardMode) return
        clipboardMode = false
        clipboardClearPending = false
        setInputView(buildKeyboardView())
        refreshSuggestionStripSoon()
    }

    private fun saveCurrentClipboardText() {
        val text = currentClipboardText() ?: return
        LocalClipboardStore.add(prefs, text, layout.clipboardRetentionDays)
        setInputView(buildKeyboardView())
    }

    private fun captureClipboardIfEnabled() {
        if (!layout.clipboardAutoSave) return
        val text = currentClipboardText() ?: return
        LocalClipboardStore.add(prefs, text, layout.clipboardRetentionDays)
    }

    private fun currentClipboardText(): String? {
        val manager = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun pasteClipboardItem(text: String) {
        currentInputConnection?.commitText(text, 1)
        LocalClipboardStore.markUsed(prefs, text, layout.clipboardRetentionDays)
        localWordBeforeCursor = ""
        pendingAddWord = null
        setInputView(buildKeyboardView())
    }

    private fun sendShiftEnter() {
        sendKeyCode(
            KeyEvent.KEYCODE_ENTER,
            extraMetaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        )
    }

    private fun openKeyboardSettings() {
        clearLatchedModifiers()
        requestHideSelf(0)
        val intent = Intent(this, KeyboardSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun requestLauncherShortcut(shortcut: LauncherShortcutContract.Shortcut) {
        val token = launcherShortcuts?.token ?: return
        val intent = Intent(LauncherShortcutContract.ACTION_RUN).apply {
            setClassName(
                LauncherShortcutContract.LAUNCHER_PACKAGE,
                LauncherShortcutContract.LAUNCHER_ACTIVITY
            )
            putExtra(LauncherShortcutContract.EXTRA_ID, shortcut.id)
            putExtra(LauncherShortcutContract.EXTRA_TOKEN, token)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            requestHideSelf(0)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Re:T-UI Launcher shortcut unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleModifier(special: Special) {
        when (special) {
            Special.CTRL -> ctrlLatched = !ctrlLatched
            Special.ALT -> altLatched = !altLatched
            Special.SUPER -> metaLatched = !metaLatched
            else -> return
        }
        setInputView(buildKeyboardView())
    }

    private fun hasLatchedModifiers(): Boolean {
        return ctrlLatched || altLatched || metaLatched
    }

    private fun clearLatchedModifiers(): Boolean {
        val changed = hasLatchedModifiers()
        ctrlLatched = false
        altLatched = false
        metaLatched = false
        return changed
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

    private fun startDirectionRepeat(keyCode: Int) {
        stopRepeat()
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                sendDirectionKeyCode(keyCode)
                step++
                repeatHandler.postDelayed(this, directionRepeatDelayMs(step))
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, DIRECTION_REPEAT_INITIAL_DELAY_MS)
    }

    private fun directionRepeatDelayMs(step: Int): Long {
        return when {
            step < 3 -> 260L
            step < 7 -> 170L
            step < 14 -> 105L
            else -> 58L
        }
    }

    private fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun cancelSuggestionRefresh() {
        if (suggestionRefreshPosted) {
            repeatHandler.removeCallbacks(suggestionRefreshRunnable)
            suggestionRefreshPosted = false
        }
    }

    private fun commit(value: String) {
        currentInputConnection?.commitText(value, 1)
    }

    private fun commitEmoji(value: String) {
        currentInputConnection?.commitText(value, 1)
        localWordBeforeCursor = ""
        pendingAddWord = null
        refreshSuggestionStripSoon()
    }

    private fun commitFromKey(value: String) {
        val finishedWord = if (isWordBoundary(value)) currentWordBeforeCursor() else null
        smartSentenceCommitText(value)?.let { smartValue ->
            commit(smartValue)
            trackCommittedText(smartValue)
            learnFinishedWord(finishedWord)
            armSentenceShift()
            return
        }
        val commitValue = initialFieldCasedText(value)
        val armShiftAfterSpace = shouldArmSentenceShiftAfterManualSpace(value)
        if (!sendTextKeyEventForMaskedField(commitValue)) {
            commit(commitValue)
        }
        trackCommittedText(commitValue)
        learnFinishedWord(finishedWord)
        if (armShiftAfterSpace) {
            armSentenceShift()
        } else if (shifted && !capsLocked) {
            shifted = false
            lastShiftTapAtMs = 0L
            setInputView(buildKeyboardView())
        } else {
            refreshSuggestionStripSoon()
        }
    }

    private fun smartSentenceCommitText(value: String): String? {
        if (!shouldApplyEnglishTypingAssist() || hasLatchedModifiers()) return null
        val punctuation = value.singleOrNull() ?: return null
        if (!isSentenceTerminal(punctuation)) return null
        val before = currentInputConnection?.getTextBeforeCursor(SENTENCE_CONTEXT_CHARS, 0)?.toString() ?: return null
        if (!shouldAutoSpaceAfterSentencePunctuation(punctuation, before)) return null
        return "$value "
    }

    private fun shouldArmSentenceShiftAfterManualSpace(value: String): Boolean {
        if (value != " " || !shouldApplyEnglishTypingAssist() || hasLatchedModifiers()) return false
        val before = currentInputConnection?.getTextBeforeCursor(SENTENCE_CONTEXT_CHARS, 0)?.toString() ?: return false
        return isSentenceBoundaryBeforeCursor(before)
    }

    private fun shouldApplyEnglishTypingAssist(): Boolean {
        if (activeLanguagePack != null) return false
        if (!layout.doubleSpacePeriod) return false
        val info = currentInfo
        if (usesNumberPad(info)) return false
        if (info != null && (isPasswordField(info) || isCommandLikeField(info))) return false
        return true
    }

    private fun shouldAutoSpaceAfterSentencePunctuation(punctuation: Char, before: String): Boolean {
        val last = before.lastOrNull() ?: return false
        if (last.isWhitespace() || isSentenceTerminal(last) || last in ",;:") return false
        if (punctuation == '.' && isLikelyNonSentenceDotContext(before, rejectDottedToken = false)) return false
        return last.isLetterOrDigit() || isSentenceClosingChar(last)
    }

    private fun isSentenceBoundaryBeforeCursor(before: String): Boolean {
        var index = before.length - 1
        while (index >= 0 && isSentenceClosingChar(before[index])) {
            index--
        }
        if (index < 0) return false
        val terminal = before[index]
        if (!isSentenceTerminal(terminal)) return false
        return terminal != '.' || !isLikelyNonSentenceDotContext(
            before.substring(0, index),
            rejectDottedToken = true
        )
    }

    private fun isLikelyNonSentenceDotContext(beforeDot: String, rejectDottedToken: Boolean): Boolean {
        val token = trailingToken(beforeDot)
        if (token.isBlank()) return true
        val lower = token.lowercase()
        if (token.lastOrNull()?.isDigit() == true) return true
        if (token.length == 1 && token.first().isLowerCase()) return true
        if (rejectDottedToken && token.contains('.')) return true
        return lower.contains("@") ||
            lower.contains("://") ||
            lower.startsWith("www") ||
            lower.contains("/") ||
            lower.contains("\\")
    }

    private fun trailingToken(value: String): String {
        var start = value.length
        while (start > 0 && !value[start - 1].isWhitespace()) {
            start--
        }
        return value.substring(start)
    }

    private fun isSentenceTerminal(char: Char): Boolean {
        return char == '.' || char == '!' || char == '?'
    }

    private fun isSentenceClosingChar(char: Char): Boolean {
        return char == '"' ||
            char == '\'' ||
            char == ')' ||
            char == ']' ||
            char == '}' ||
            char == '>' ||
            char == '”' ||
            char == '’'
    }

    private fun armSentenceShift() {
        if (capsLocked) {
            refreshSuggestionStripSoon()
            return
        }
        shifted = true
        lastShiftTapAtMs = 0L
        setInputView(buildKeyboardView())
    }

    private fun applyActiveWordCasing(word: String): String {
        if (activeLanguagePack != null) return word
        return when {
            capsLocked -> word.uppercase()
            shifted -> word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
            else -> word
        }
    }

    private fun commitSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val currentWord = currentWordBeforeCursorContext()
        if (currentWord.value.isNotEmpty() && !currentWord.fromLocalFallback) {
            ic.deleteSurroundingText(currentWord.value.length, 0)
        }
        ic.commitText(suggestionCommitText(applyActiveWordCasing(word), currentWord), 1)
        pendingAddWord = null
        localWordBeforeCursor = ""
        activeRecordAcceptedWord(word)
        if (shifted && !capsLocked) {
            shifted = false
            lastShiftTapAtMs = 0L
            setInputView(buildKeyboardView())
        } else {
            refreshSuggestionStripSoon()
        }
    }

    private fun commitNextWordAfterActive(word: String) {
        val normalized = activeNormalizeWord(word) ?: return
        val ic = currentInputConnection ?: return
        learnFinishedWord(currentWordBeforeCursor())
        ic.commitText(" ${activeDisplayWord(normalized)} ", 1)
        pendingAddWord = null
        localWordBeforeCursor = ""
        activeRecordAcceptedWord(normalized)
        if (shifted && !capsLocked) {
            shifted = false
            lastShiftTapAtMs = 0L
            setInputView(buildKeyboardView())
        } else {
            refreshSuggestionStripSoon()
        }
    }

    private fun handleSpace() {
        if (layout.doubleSpacePeriod && commitDoubleSpacePeriod()) return
        commitFromKey(" ")
    }

    private fun commitDoubleSpacePeriod(): Boolean {
        if (shouldSendPlainKeyEventsForCurrentField()) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(96, 0)?.toString() ?: return false
        if (!before.endsWith(" ") || before.endsWith("  ")) return false
        val wordEnd = before.length - 1
        if (wordEnd <= 0 || !isWordChar(before[wordEnd - 1])) return false
        var wordStart = wordEnd
        while (wordStart > 0 && isWordChar(before[wordStart - 1])) {
            wordStart--
        }
        val finishedWord = before.substring(wordStart, wordEnd)
        ic.deleteSurroundingText(1, 0)
        commit(". ")
        localWordBeforeCursor = ""
        learnFinishedWord(finishedWord)
        armSentenceShift()
        return true
    }

    private fun armShiftForEmptyInputIfNeeded(): Boolean {
        if (!shouldApplyEnglishTypingAssist() || hasLatchedModifiers() || shifted || capsLocked) return false
        if (!isCurrentInputEmpty()) return false
        shifted = true
        lastShiftTapAtMs = 0L
        return true
    }

    private fun initialFieldCasedText(value: String): String {
        if (!shouldApplyEnglishTypingAssist() || hasLatchedModifiers()) return value
        if (value.length != 1 || !value.first().isLetter()) return value
        if (!isCurrentInputEmpty()) return value
        return value.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

    private fun isCurrentInputEmpty(): Boolean {
        val ic = currentInputConnection ?: return false
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted?.text != null) {
            return extracted.text.isEmpty() &&
                extracted.selectionStart == 0 &&
                extracted.selectionEnd == 0
        }
        val before = ic.getTextBeforeCursor(1, 0) ?: return false
        val after = ic.getTextAfterCursor(1, 0) ?: return false
        return before.isEmpty() && after.isEmpty()
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
        if (hasLatchedModifiers() || shouldSendPlainKeyEventsForCurrentField()) {
            sendKeyCode(KeyEvent.KEYCODE_DEL)
            return
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            localWordBeforeCursor = ""
        } else {
            if (layout.deleteWholeWord && deleteWordBeforeCursor(ic)) {
                refreshSuggestionStripSoon()
                return
            }
            ic.deleteSurroundingText(1, 0)
            if (localWordBeforeCursor.isNotEmpty()) {
                localWordBeforeCursor = localWordBeforeCursor.dropLast(1)
            }
        }
        refreshSuggestionStripSoon()
    }

    private fun deleteWordBeforeCursor(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(128, 0)?.toString() ?: return false
        if (before.isEmpty()) return false
        var wordEnd = before.length
        while (wordEnd > 0 && before[wordEnd - 1].isWhitespace()) {
            wordEnd--
        }
        if (wordEnd <= 0 || !isWordChar(before[wordEnd - 1])) return false
        var wordStart = wordEnd
        while (wordStart > 0 && isWordChar(before[wordStart - 1])) {
            wordStart--
        }
        val deleteCount = before.length - wordStart
        if (deleteCount <= 0) return false
        ic.deleteSurroundingText(deleteCount, 0)
        localWordBeforeCursor = ""
        return true
    }

    private fun forwardDelete() {
        sendKeyCode(KeyEvent.KEYCODE_FORWARD_DEL)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        learnFinishedWord(currentWordBeforeCursor())
        localWordBeforeCursor = ""
        val action = currentImeAction()
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendKeyCode(KeyEvent.KEYCODE_ENTER, ic)
        }
        refreshSuggestionStripSoon()
    }

    private fun sendKeyCode(
        keyCode: Int,
        ic: InputConnection? = currentInputConnection,
        extraMetaState: Int = 0
    ) {
        ic ?: return
        val metaState = latchedMetaState() or extraMetaState
        dispatchKeyCode(ic, keyCode, metaState)
        val clearedModifiers = clearLatchedModifiers()
        if (clearedModifiers) {
            setInputView(buildKeyboardView())
            return
        }
        refreshSuggestionStripSoon()
    }

    private fun sendDirectionKeyCode(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val hadModifiers = hasLatchedModifiers()
        val handled = dispatchKeyCode(ic, keyCode, latchedMetaState())
        val clearedModifiers = clearLatchedModifiers()
        if (!handled && !hadModifiers) {
            moveCursorWithinActiveInput(keyCode)
        }
        if (clearedModifiers) {
            setInputView(buildKeyboardView())
            return
        }
        refreshSuggestionStripSoon()
    }

    private fun sendTextKeyEventForMaskedField(value: String): Boolean {
        if (!shouldSendPlainKeyEventsForCurrentField()) return false
        val keyCode = keyCodeForText(value) ?: return false
        val ic = currentInputConnection ?: return false
        return dispatchKeyCode(ic, keyCode, 0)
    }

    private fun shouldSendPlainKeyEventsForCurrentField(): Boolean {
        if (hasLatchedModifiers()) return false
        val inputType = currentInfo?.inputType ?: return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
    }

    private fun dispatchKeyCode(ic: InputConnection, keyCode: Int, metaState: Int): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        val downHandled = ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        val upHandled = ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        return downHandled || upHandled
    }

    private fun sendTextWithLatchedModifiers(value: String): Boolean {
        if (!hasLatchedModifiers()) return false
        val keyCode = keyCodeForText(value)
        if (keyCode == null) {
            if (clearLatchedModifiers()) setInputView(buildKeyboardView())
            return false
        }
        sendKeyCode(keyCode)
        return true
    }

    private fun latchedMetaState(): Int {
        var state = 0
        if (ctrlLatched) state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altLatched) state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (metaLatched) state = state or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return state
    }

    private fun keyCodeForText(value: String): Int? {
        val char = value.singleOrNull()?.lowercaseChar() ?: return null
        return when (char) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '/' -> KeyEvent.KEYCODE_SLASH
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            else -> null
        }
    }

    private fun learnFinishedWord(word: String?) {
        if (word.isNullOrBlank()) return
        val normalized = activeNormalizeWord(word) ?: return
        if (activeContainsKnownWord(normalized)) {
            pendingAddWord = null
            if (layout.learnLocalWords) {
                activeLearnTypedWord(normalized, force = false)
            }
            return
        }
        pendingAddWord = normalized
    }

    private fun refreshSuggestionStripSoon() {
        if (suggestionRefreshPosted) return
        suggestionRefreshPosted = true
        repeatHandler.post(suggestionRefreshRunnable)
    }

    private fun refreshSuggestionStrip() {
        if (emojiMode || clipboardMode) return
        val offersSuggestions = shouldOfferSuggestions()
        if (offersSuggestions != (suggestionStrip != null)) {
            setInputView(buildKeyboardView())
            return
        }
        val strip = suggestionStrip ?: return
        if (!offersSuggestions) return
        populateSuggestionStrip(strip)
    }

    private fun currentWordBeforeCursor(): String {
        return currentWordBeforeCursorContext().value
    }

    private fun currentWordBeforeCursorContext(): CursorWord {
        val text = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString()
            ?: return CursorWord(localWordBeforeCursor, fromLocalFallback = localWordBeforeCursor.isNotEmpty())
        if (text.isEmpty()) return CursorWord(localWordBeforeCursor, fromLocalFallback = localWordBeforeCursor.isNotEmpty())
        var start = text.length
        while (start > 0 && isWordChar(text[start - 1])) {
            start--
        }
        return CursorWord(text.substring(start), fromLocalFallback = false)
    }

    private fun previousWordBeforeCursor(): String? {
        val text = currentInputConnection?.getTextBeforeCursor(128, 0)?.toString() ?: return null
        if (text.isBlank()) return null
        var end = text.length
        var crossedBoundary = false
        while (end > 0 && !isWordChar(text[end - 1])) {
            crossedBoundary = true
            end--
        }
        if (!crossedBoundary || end <= 0) return null
        var start = end
        while (start > 0 && isWordChar(text[start - 1])) {
            start--
        }
        return text.substring(start, end).takeIf { it.isNotBlank() }
    }

    private fun previousWordsBeforeCursor(limit: Int): List<String> {
        val safeLimit = limit.coerceIn(1, 5)
        val text = currentInputConnection?.getTextBeforeCursor(192, 0)?.toString()
            ?: return if (localWordBeforeCursor.isNotBlank()) listOf(localWordBeforeCursor) else emptyList()
        if (text.isBlank()) return emptyList()
        val out = ArrayDeque<String>()
        var index = text.length
        while (index > 0 && out.size < safeLimit) {
            while (index > 0 && !isWordChar(text[index - 1])) {
                index--
            }
            val end = index
            while (index > 0 && isWordChar(text[index - 1])) {
                index--
            }
            if (end > index) {
                out.addFirst(text.substring(index, end))
            }
        }
        return out.toList()
    }

    private fun trackCommittedText(value: String) {
        value.forEach { char ->
            localWordBeforeCursor = if (isWordChar(char)) {
                (localWordBeforeCursor + char).takeLast(64)
            } else {
                ""
            }
        }
    }

    private fun suggestionCommitText(word: String, currentWord: CursorWord): String {
        if (!currentWord.fromLocalFallback || currentWord.value.isEmpty()) return "$word "
        if (!word.startsWith(currentWord.value, ignoreCase = true)) return "$word "
        return "${word.drop(currentWord.value.length)} "
    }

    private fun isWordBoundary(value: String): Boolean {
        if (value.isEmpty()) return false
        return value.any { !isWordChar(it) }
    }

    private fun isWordChar(char: Char): Boolean {
        return activeLanguagePack?.isWordChar(char) ?: LocalDictionary.isWordChar(char)
    }

    private fun activeNormalizeWord(word: String): String? {
        val pack = activeLanguagePack
        return if (pack == null) LocalDictionary.normalizeWord(word) else pack.normalizeWord(word)
    }

    private fun activeContainsKnownWord(word: String): Boolean {
        val pack = activeLanguagePack
        return if (pack == null) {
            LocalDictionary.containsKnownWord(prefs, word)
        } else {
            LanguagePackDictionary.containsKnownWord(pack, prefs, word)
        }
    }

    private fun activeSuggest(prefix: String, limit: Int): List<String> {
        val pack = activeLanguagePack
        return if (pack == null) {
            LocalDictionary.suggest(prefs, prefix, limit)
        } else {
            LanguagePackDictionary.suggest(pack, prefs, prefix, limit)
        }
    }

    private fun activeSuggestCurrentWordAlternatives(word: String, limit: Int): List<String> {
        val pack = activeLanguagePack
        return if (pack == null) {
            LocalDictionary.suggestCurrentWordAlternatives(prefs, word, limit)
        } else {
            LanguagePackDictionary.suggestCurrentWordAlternatives(pack, prefs, word, limit)
        }
    }

    private fun activeSuggestNextWords(previousWord: String?, limit: Int): List<String> {
        return if (activeLanguagePack == null) {
            LocalDictionary.suggestNextWords(prefs, previousWord, limit)
        } else {
            emptyList()
        }
    }

    private fun activeLearnTypedWord(word: String, force: Boolean): Boolean {
        val pack = activeLanguagePack
        return if (pack == null) {
            LocalDictionary.learnTypedWord(prefs, word, force)
        } else {
            LanguagePackDictionary.learnTypedWord(pack, prefs, word, force)
        }
    }

    private fun activeRecordAcceptedWord(word: String) {
        val pack = activeLanguagePack
        if (pack == null) {
            LocalDictionary.recordAcceptedWord(prefs, word)
        } else {
            LanguagePackDictionary.recordAcceptedWord(pack, prefs, word)
        }
    }

    private fun activeDisplayWord(word: String): String {
        return activeLanguagePack?.normalizeWord(word) ?: LocalDictionary.displayWord(word)
    }

    private fun applyEditorInfo(info: EditorInfo?) {
        if (info == null) return
        contextLabel = compactPackageName(info.packageName)
        modeLabel = if (isCommandLikeField(info)) "COMMAND" else "TEXT"
        info.extras?.let { applyContextBundle(it, ThemeSource.LAUNCHER) }
        applyPrivateImeOptions(info.privateImeOptions)
    }

    private fun resetTransientLayoutState() {
        emojiMode = false
        clipboardMode = false
        clipboardClearPending = false
        emojiCategoryIndex = 0
        symbols = false
        shifted = false
        capsLocked = false
        ctrlLatched = false
        altLatched = false
        metaLatched = false
        lastShiftTapAtMs = 0L
        pendingAddWord = null
        localWordBeforeCursor = ""
    }

    private fun applyPrivateImeOptions(raw: String?) {
        if (raw.isNullOrBlank()) return
        val payload = when {
            raw.startsWith(PRIVATE_OPTIONS_PREFIX) -> raw.substringAfter(':', raw)
            raw.contains(RetuiVisualContract.BG + "=") ||
                raw.contains("theme_bg=") ||
                raw.contains("module_bg_color=") -> raw
            else -> return
        }
        val bundle = Bundle()
        payload.split(';').forEach { token ->
            val trimmed = token.trim()
            val index = trimmed.indexOf('=')
            if (index <= 0 || index >= trimmed.length - 1) return@forEach
            bundle.putString(trimmed.substring(0, index).trim(), trimmed.substring(index + 1).trim())
        }
        if (bundle.keySet().isNotEmpty()) applyContextBundle(bundle, ThemeSource.LAUNCHER)
    }

    private fun applyContextBundle(bundle: Bundle, source: ThemeSource): Boolean {
        val previousTheme = theme
        val previousSymbols = symbols
        val frameChanged = source == ThemeSource.LAUNCHER && frameRenderer.accept(bundle)
        val shortcutsChanged = source == ThemeSource.LAUNCHER && applyLauncherShortcuts(bundle)
        if (source == ThemeSource.LAUNCHER) {
            if (containsThemeValue(bundle)) {
                val launcherTheme = themeFromBundle(
                    readThemeSnapshot(KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX, ThemeState()),
                    bundle
                )
                saveThemeSnapshot(launcherTheme, KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX)
                if (!hasKeyboardThemeOverride()) {
                    theme = launcherTheme
                }
            }
        } else {
            theme = themeFromBundle(theme, bundle)
        }

        RetuiVisualContract.string(bundle, RetuiVisualContract.CONTEXT, "keyboard_context", "path")?.let { contextLabel = compactContext(it) }
        RetuiVisualContract.string(bundle, RetuiVisualContract.MODE, "keyboard_mode", "mode")?.let { modeLabel = it.uppercase().take(14) }
        RetuiVisualContract.booleanOrNull(bundle, "keyboard_symbols", "symbols")?.let { symbols = it }
        return theme != previousTheme || symbols != previousSymbols || frameChanged || shortcutsChanged
    }

    private fun applyLauncherShortcuts(bundle: Bundle): Boolean {
        if (currentInfo?.packageName != LauncherShortcutContract.LAUNCHER_PACKAGE) return false
        val raw = bundle.getString(LauncherShortcutContract.BUNDLE_KEY) ?: return false
        val next = LauncherShortcutContract.accept(prefs, raw) ?: return false
        if (next == launcherShortcuts) return false
        launcherShortcuts = next
        return true
    }

    private fun themeFromBundle(base: ThemeState, bundle: Bundle): ThemeState {
        val C = RetuiVisualContract
        return base.copy(
            bg = C.color(bundle, base.bg, C.BG),
            text = C.color(bundle, base.text, C.TEXT),
            border = C.color(bundle, base.border, C.BORDER),
            panelBg = C.color(bundle, base.panelBg, C.TERMINAL_BG, C.PANEL_BG),
            headerBg = C.color(bundle, base.headerBg, C.HEADER_BG),
            headerTabBorder = C.color(
                bundle,
                C.color(bundle, base.headerTabBorder, C.PANEL_BORDER, C.BORDER),
                "terminal_header_border_color",
                "terminal_header_tab_border_color",
                "header_tab_border_color"
            ),
            headerText = C.color(bundle, base.headerText, C.HEADER_TEXT, C.PANEL_TEXT),
            keyBg = C.color(bundle, base.keyBg, C.BUTTON_BG, C.INPUT_BG),
            keyText = C.color(bundle, base.keyText, C.BUTTON_TEXT, C.INPUT_TEXT, C.PANEL_TEXT),
            specialKeyBg = C.color(
                bundle,
                base.specialKeyBg,
                "keyboard_special_key_bg",
                "keyboard_special_key_background",
                "special_key_bg_color",
                "special_key_background_color"
            ),
            specialKeyText = C.color(
                bundle,
                base.specialKeyText,
                "keyboard_special_key_text",
                "keyboard_special_key_text_color",
                "special_key_text_color"
            ),
            outputBg = C.color(bundle, base.outputBg, C.OUTPUT_BG),
            outputBorder = C.color(bundle, base.outputBorder, C.OUTPUT_BORDER, C.BORDER),
            fontSizeSp = C.int(bundle, base.fontSizeSp, C.INPUT_FONT_SIZE, "keyboard_font_size").coerceIn(10, 24),
            dashedBorders = C.boolean(bundle, base.dashedBorders, C.DASHED_BORDERS),
            dashLengthDp = C.int(bundle, base.dashLengthDp, C.DASHED_BORDER_DASH_LENGTH, "dash_length", "terminal_dash_length").coerceIn(0, 48),
            dashGapDp = C.int(bundle, base.dashGapDp, C.DASHED_BORDER_GAP_LENGTH, "dash_gap", "terminal_dash_gap").coerceIn(0, 48),
            dashedStrokeWidthDp = C.float(
                bundle,
                base.dashedStrokeWidthDp,
                C.DASHED_BORDER_STROKE_WIDTH_DP,
                "dashed_border_stroke_width",
                "dash_stroke_width"
            ).let { if (it.isFinite()) it.coerceIn(0.5f, 8f) else base.dashedStrokeWidthDp },
            moduleCornerRadiusDp = C.int(bundle, base.moduleCornerRadiusDp, C.MODULE_CORNER_RADIUS, "corner_radius", "corner_radius_dp").coerceIn(0, 48),
            outputCornerRadiusDp = C.int(bundle, base.outputCornerRadiusDp, C.OUTPUT_CORNER_RADIUS, "terminal_corner_radius").coerceIn(0, 48),
            headerCornerRadiusDp = C.int(bundle, base.headerCornerRadiusDp, C.HEADER_CORNER_RADIUS, "terminal_header_corner_radius").coerceIn(0, 48),
            moduleBodyTextSizeSp = C.int(bundle, base.moduleBodyTextSizeSp, C.BODY_TEXT_SIZE, "output_font_size").coerceIn(8, 32),
            outputHeaderTextSizeSp = C.int(bundle, base.outputHeaderTextSizeSp, C.OUTPUT_HEADER_TEXT_SIZE, C.HEADER_TEXT_SIZE, "header_font_size").coerceIn(8, 32),
            cyberdeckMode = C.boolean(bundle, base.cyberdeckMode, C.CYBERDECK_MODE),
            crtFilter = C.boolean(bundle, base.crtFilter, C.CRT_FILTER)
        )
    }

    private fun loadPersistedTheme(): Boolean {
        val prefix = when {
            hasKeyboardThemeOverride() -> THEME_OVERRIDE_PREFIX
            prefs.getBoolean(KeyboardPrefs.KEY_THEME_LAUNCHER_AVAILABLE, false) -> KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX
            else -> null
        }
        val next = readThemeSnapshot(prefix, ThemeState())
        val changed = next != theme
        theme = next
        return changed
    }

    private fun hasKeyboardThemeOverride(): Boolean {
        return prefs.getBoolean(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN, false)
    }

    private fun readThemeSnapshot(prefix: String?, fallback: ThemeState): ThemeState {
        if (prefix == null) return fallback
        return fallback.copy(
            bg = prefs.getInt(prefix + "bg", fallback.bg),
            text = prefs.getInt(prefix + "text", fallback.text),
            border = prefs.getInt(prefix + "border", fallback.border),
            panelBg = prefs.getInt(prefix + "panelBg", fallback.panelBg),
            headerBg = prefs.getInt(prefix + "headerBg", fallback.headerBg),
            headerTabBorder = prefs.getInt(prefix + "headerTabBorder", fallback.headerTabBorder),
            headerText = prefs.getInt(prefix + "headerText", fallback.headerText),
            keyBg = prefs.getInt(prefix + "keyBg", fallback.keyBg),
            keyText = prefs.getInt(prefix + "keyText", fallback.keyText),
            specialKeyBg = prefs.getInt(prefix + "specialKeyBg", fallback.specialKeyBg),
            specialKeyText = prefs.getInt(prefix + "specialKeyText", fallback.specialKeyText),
            outputBg = prefs.getInt(prefix + "outputBg", fallback.outputBg),
            outputBorder = prefs.getInt(prefix + "outputBorder", fallback.outputBorder),
            fontSizeSp = prefs.getInt(prefix + "fontSizeSp", fallback.fontSizeSp),
            dashedBorders = prefs.getBoolean(prefix + "dashedBorders", fallback.dashedBorders),
            dashLengthDp = prefs.getInt(prefix + "dashLengthDp", fallback.dashLengthDp),
            dashGapDp = prefs.getInt(prefix + "dashGapDp", fallback.dashGapDp),
            dashedStrokeWidthDp = prefs.getFloat(prefix + "dashedStrokeWidthDp", fallback.dashedStrokeWidthDp),
            moduleCornerRadiusDp = prefs.getInt(prefix + "moduleCornerRadiusDp", fallback.moduleCornerRadiusDp),
            outputCornerRadiusDp = prefs.getInt(prefix + "outputCornerRadiusDp", fallback.outputCornerRadiusDp),
            headerCornerRadiusDp = prefs.getInt(prefix + "headerCornerRadiusDp", fallback.headerCornerRadiusDp),
            moduleBodyTextSizeSp = prefs.getInt(prefix + "moduleBodyTextSizeSp", fallback.moduleBodyTextSizeSp),
            outputHeaderTextSizeSp = prefs.getInt(prefix + "outputHeaderTextSizeSp", fallback.outputHeaderTextSizeSp),
            cyberdeckMode = prefs.getBoolean(prefix + "cyberdeckMode", fallback.cyberdeckMode),
            crtFilter = prefs.getBoolean(prefix + "crtFilter", fallback.crtFilter)
        )
    }

    private fun saveThemeSnapshot(next: ThemeState, prefix: String) {
        prefs.edit()
            .putInt(prefix + "bg", next.bg)
            .putInt(prefix + "text", next.text)
            .putInt(prefix + "border", next.border)
            .putInt(prefix + "panelBg", next.panelBg)
            .putInt(prefix + "headerBg", next.headerBg)
            .putInt(prefix + "headerTabBorder", next.headerTabBorder)
            .putInt(prefix + "headerText", next.headerText)
            .putInt(prefix + "keyBg", next.keyBg)
            .putInt(prefix + "keyText", next.keyText)
            .putInt(prefix + "specialKeyBg", next.specialKeyBg)
            .putInt(prefix + "specialKeyText", next.specialKeyText)
            .putInt(prefix + "outputBg", next.outputBg)
            .putInt(prefix + "outputBorder", next.outputBorder)
            .putInt(prefix + "fontSizeSp", next.fontSizeSp)
            .putBoolean(prefix + "dashedBorders", next.dashedBorders)
            .putInt(prefix + "dashLengthDp", next.dashLengthDp)
            .putInt(prefix + "dashGapDp", next.dashGapDp)
            .putFloat(prefix + "dashedStrokeWidthDp", next.dashedStrokeWidthDp)
            .putInt(prefix + "moduleCornerRadiusDp", next.moduleCornerRadiusDp)
            .putInt(prefix + "outputCornerRadiusDp", next.outputCornerRadiusDp)
            .putInt(prefix + "headerCornerRadiusDp", next.headerCornerRadiusDp)
            .putInt(prefix + "moduleBodyTextSizeSp", next.moduleBodyTextSizeSp)
            .putInt(prefix + "outputHeaderTextSizeSp", next.outputHeaderTextSizeSp)
            .putBoolean(prefix + "cyberdeckMode", next.cyberdeckMode)
            .putBoolean(prefix + "crtFilter", next.crtFilter)
            .putBoolean(KeyboardPrefs.KEY_THEME_LAUNCHER_AVAILABLE, true)
            .putLong(KeyboardPrefs.KEY_THEME_LAUNCHER_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun containsThemeValue(bundle: Bundle): Boolean {
        return RetuiVisualContract.hasVisualPayload(bundle) ||
            bundle.containsKey("keyboard_special_key_bg") ||
            bundle.containsKey("keyboard_special_key_text")
    }

    private fun isCommandLikeField(info: EditorInfo): Boolean {
        val packageName = info.packageName?.lowercase().orEmpty()
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val klass = info.inputType and InputType.TYPE_MASK_CLASS
        return packageName.contains("termux") ||
            packageName.contains("terminal") ||
            action == EditorInfo.IME_ACTION_GO ||
            action == EditorInfo.IME_ACTION_SEND ||
            klass == InputType.TYPE_CLASS_TEXT && info.hintText?.contains("$") == true
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
        val specialKeyBg: Int = 0,
        val specialKeyText: Int = Color.WHITE,
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
        val longKeyCode: Int? = null,
        val longSpecial: Special? = null,
        val accentVariants: List<String> = emptyList(),
        val edgeAlias: Boolean = false,
        val specialStyle: Boolean = false
    )

    private data class LongPressChoice(
        val text: String? = null,
        val shortcut: LauncherShortcutContract.Shortcut? = null
    )

    private class LongPressPopup(
        val popup: PopupWindow,
        private val row: LinearLayout,
        private val cells: List<View>,
        private val cellWidth: Int,
        private val activeBackground: Drawable
    ) {
        private val location = IntArray(2)

        fun indexForRawX(rawX: Float): Int {
            row.getLocationOnScreen(location)
            val relativeX = rawX - location[0]
            return (relativeX / cellWidth).toInt().coerceIn(0, cells.lastIndex)
        }

        fun select(index: Int) {
            cells.forEachIndexed { cellIndex, cell ->
                cell.background = if (cellIndex == index) activeBackground.constantState?.newDrawable() ?: activeBackground else null
            }
        }
    }

    private class DirectionPadPopup(
        val popup: PopupWindow,
        private val cells: Map<Int, TextView>,
        private val inactiveBackground: Drawable,
        private val activeBackground: Drawable
    ) {
        fun select(keyCode: Int?) {
            cells.forEach { (cellKeyCode, cell) ->
                val background = if (cellKeyCode == keyCode) activeBackground else inactiveBackground
                cell.background = background.constantState?.newDrawable() ?: background
            }
        }
    }

    private class DirectionPadIconView(
        context: Context,
        color: Int,
        density: Float
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            setShadowLayer(2f * density, 0f, 1f * density, Color.argb(130, 0, 0, 0))
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val size = min(width, height) * 0.58f
            val stem = size * 0.16f
            val head = size * 0.28f
            val half = size / 2f

            path.reset()
            path.addRect(cx - stem / 2f, cy - half + head, cx + stem / 2f, cy + half - head, Path.Direction.CW)
            path.addRect(cx - half + head, cy - stem / 2f, cx + half - head, cy + stem / 2f, Path.Direction.CW)
            addArrowHead(cx, cy - half, 0f, -1f, head)
            addArrowHead(cx, cy + half, 0f, 1f, head)
            addArrowHead(cx - half, cy, -1f, 0f, head)
            addArrowHead(cx + half, cy, 1f, 0f, head)
            canvas.drawPath(path, paint)
        }

        private fun addArrowHead(tipX: Float, tipY: Float, dx: Float, dy: Float, size: Float) {
            val baseX = tipX - dx * size
            val baseY = tipY - dy * size
            val sideX = -dy * size * 0.55f
            val sideY = dx * size * 0.55f
            path.moveTo(tipX, tipY)
            path.lineTo(baseX + sideX, baseY + sideY)
            path.lineTo(baseX - sideX, baseY - sideY)
            path.close()
        }
    }

    private class GlideLayerFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val body = getChildAt(0)
            if (body == null) {
                setMeasuredDimension(width, 0)
                return
            }
            body.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val height = body.measuredHeight
            for (index in 1 until childCount) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until childCount) {
                getChildAt(index).layout(0, 0, right - left, bottom - top)
            }
        }
    }

    private class GlideTrailView(
        context: Context,
        private val strokeColor: Int,
        density: Float
    ) : View(context) {
        private val location = IntArray(2)
        private val points = mutableListOf<GlideTrailPoint>()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = withAlpha(strokeColor, 70)
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = withAlpha(strokeColor, 230)
        }

        fun beginRaw(rawX: Float, rawY: Float) {
            points.clear()
            addRaw(rawX, rawY)
        }

        fun extendRaw(rawX: Float, rawY: Float) {
            addRaw(rawX, rawY)
        }

        fun clear() {
            if (points.isEmpty()) return
            points.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (points.size < 2) return
            val path = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) {
                    path.moveTo(point.x, point.y)
                } else {
                    path.lineTo(point.x, point.y)
                }
            }
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, linePaint)
        }

        private fun addRaw(rawX: Float, rawY: Float) {
            getLocationOnScreen(location)
            val x = rawX - location[0]
            val y = rawY - location[1]
            val last = points.lastOrNull()
            if (last != null) {
                val dx = x - last.x
                val dy = y - last.y
                if ((dx * dx) + (dy * dy) < 16f) return
            }
            points.add(GlideTrailPoint(x, y))
            invalidate()
        }

        private fun withAlpha(color: Int, alpha: Int): Int {
            return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    private data class GlideTrailPoint(
        val x: Float,
        val y: Float
    )

    private data class KeyboardViewSignature(
        val orientation: Int,
        val layout: KeyboardLayoutSettings,
        val theme: ThemeState,
        val inputType: Int,
        val imeAction: Int,
        val usesNumberPad: Boolean,
        val offersSuggestions: Boolean,
        val symbols: Boolean,
        val emojiMode: Boolean,
        val clipboardMode: Boolean,
        val clipboardClearPending: Boolean,
        val languageId: String,
        val emojiCategoryIndex: Int
    )

    private data class SuggestionChip(
        val label: String,
        val word: String,
        val action: SuggestionAction,
        val weight: Float
    )

    private data class CursorWord(
        val value: String,
        val fromLocalFallback: Boolean
    )

    private data class GlideKeyHit(
        val view: View,
        val char: Char,
        val bounds: Rect
    )

    private enum class SuggestionAction {
        ADD_WORD,
        COMMIT,
        COMMIT_NEXT_WORD
    }

    private enum class Special {
        BACKSPACE,
        FORWARD_DELETE,
        ENTER,
        SHIFT,
        SPACE,
        SYMBOLS,
        LANGUAGE,
        CTRL,
        ALT,
        SUPER,
        DIRECTION_PAD,
        SHIFT_ENTER,
        EMOJI_PICKER,
        SETTINGS,
        HIDE,
        SPACER
    }

    private enum class ThemeSource {
        LAUNCHER,
        PREVIEW
    }

    private abstract class TranslucentDrawable : Drawable() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class CyberPanelDrawable(
        private val fillColor: Int,
        private val borderColor: Int,
        strokeWidthPx: Float,
        private val notch: Boolean
    ) : TranslucentDrawable() {
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

    }

    private class KeyboardBackgroundDrawable(
        fillColor: Int,
        private val bitmap: Bitmap,
        opacityPercent: Int
    ) : TranslucentDrawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val source = Rect()

        init {
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = fillColor
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

    }

    private class CrtOverlayDrawable(
        density: Float,
        accentColor: Int
    ) : TranslucentDrawable() {
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

    }

    companion object {
        const val ACTION_APPLY_CONTEXT = "com.dvil.retui.keyboard.APPLY_CONTEXT"
        const val ACTION_APPLY_THEME = "com.dvil.retui.keyboard.APPLY_THEME"
        const val ACTION_REFRESH_SETTINGS = "com.dvil.retui.keyboard.REFRESH_SETTINGS"
        const val PRIVATE_OPTIONS_PREFIX = "com.dvil.retui.keyboard"
        private const val THEME_OVERRIDE_PREFIX = "theme."
        private const val REPEAT_INITIAL_DELAY_MS = 260L
        private const val REPEAT_INTERVAL_MS = 42L
        private const val DIRECTION_REPEAT_INITIAL_DELAY_MS = 360L
        private const val SHIFT_DOUBLE_TAP_MS = 360L
        private const val GLIDE_TRAIL_HOLD_MS = 180L
        private const val KEY_PREVIEW_TIMEOUT_MS = 700L
        private const val ACTIVE_ADD_WORD_MIN_LENGTH = 4
        private const val SENTENCE_CONTEXT_CHARS = 128
        private const val NAV_MODE_GESTURAL = 2
        private const val ICON_BACKSPACE = "⌫"
        private const val ICON_CLIPBOARD = "⧉"
        private const val ICON_CONTEXT = "⌘"
        private const val ICON_DONE = "✓"
        private const val ICON_DPAD = "↕↔"
        private const val ICON_DOWN = "↓"
        private const val ICON_EMOJI = "☺"
        private const val ICON_ENTER = "↵"
        private const val ICON_ENTER_GO = "→"
        private const val ICON_ESCAPE = "ESC"
        private const val ICON_HIDE = "⌄"
        private const val ICON_LEFT = "←"
        private const val ICON_RIGHT = "→"
        private const val ICON_RECENTS = "◷"
        private const val ICON_SEARCH = "⌕"
        private const val ICON_SEND = "➤"
        private const val ICON_SETTINGS = "⚙"
        private const val ICON_SHIFT = "⇧"
        private const val ICON_TAB = "TAB"
        private const val ICON_UP = "↑"
    }
}
