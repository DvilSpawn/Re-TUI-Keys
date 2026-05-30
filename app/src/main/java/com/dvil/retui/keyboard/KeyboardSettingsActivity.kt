package com.dvil.retui.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class KeyboardSettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var list: LinearLayout
    private lateinit var settingsFrame: FrameLayout
    private lateinit var previewDock: LinearLayout
    private lateinit var previewInput: EditText
    private var theme = SettingsTheme()
    private var themeDraft = SettingsTheme()
    private var topInsetPx = 0
    private var bottomInsetPx = 0
    private var imeInsetPx = 0
    private var dictionaryEditorOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(KeyboardPrefs.PREFS_NAME, MODE_PRIVATE)
        KeyboardPrefs.migrateLayout(prefs)
        theme = loadSettingsTheme(intent)
        themeDraft = theme
        configureWindow()
        setContentView(settingsView())
        showPreviewKeyboard()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        theme = loadSettingsTheme(intent)
        themeDraft = theme
        configureWindow()
        setContentView(settingsView())
        showPreviewKeyboard()
    }

    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = theme.bg
        }
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onResume() {
        super.onResume()
        showPreviewKeyboard()
    }

    @Deprecated("Deprecated in platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_BACKGROUND -> {
                if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                    }
                }
                prefs.edit().putString(KeyboardPrefs.KEY_BACKGROUND_IMAGE_URI, uri.toString()).apply()
                rebuildRows()
                refreshKeyboard()
            }
            REQUEST_PROFILE_BACKUP -> {
                backupProfile(uri)
            }
            REQUEST_PROFILE_RESTORE -> {
                restoreProfile(uri)
            }
        }
    }

    private fun settingsView(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(theme.bg)
        root.clipToPadding = false
        root.clipChildren = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && theme.crtFilter) {
            root.foreground = TerminalCrtOverlayDrawable(resources.displayMetrics.density, theme.inputText)
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            applySystemInsets(insets)
            insets
        }

        settingsFrame = FrameLayout(this)
        settingsFrame.clipChildren = false
        settingsFrame.clipToPadding = false
        root.addView(settingsFrame, FrameLayout.LayoutParams(-1, -1))

        val dock = previewDockView()
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceMargins() }

        rebuildRows()
        root.post {
            updateSurfaceMargins()
            root.requestApplyInsets()
        }
        return root
    }

    private fun rebuildRows() {
        if (!::settingsFrame.isInitialized) return
        settingsFrame.removeAllViews()

        val window = FrameLayout(this)
        window.clipToPadding = false
        window.clipChildren = false
        settingsFrame.addView(window, FrameLayout.LayoutParams(-1, -1))

        val windowBorder = FrameLayout(this)
        windowBorder.clipToPadding = false
        windowBorder.clipChildren = false
        windowBorder.setPadding(dp(14), dp(30), dp(14), dp(14))
        windowBorder.background = panelDrawable(
            fill = theme.panelBg,
            stroke = theme.border,
            strokeDp = 1.5f,
            radiusDp = theme.outputCornerRadiusDp,
            notch = true
        )
        val borderParams = FrameLayout.LayoutParams(-1, -1)
        borderParams.topMargin = dp(18)
        borderParams.bottomMargin = dp(2)
        window.addView(windowBorder, borderParams)

        val windowContent = LinearLayout(this)
        windowContent.orientation = LinearLayout.VERTICAL
        windowContent.clipToPadding = false
        windowBorder.addView(windowContent, FrameLayout.LayoutParams(-1, -1))

        val title = terminalTab(getString(R.string.settings_title).uppercase(Locale.US), minWidthDp = 160)
        val titleParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        titleParams.leftMargin = dp(44)
        titleParams.topMargin = dp(8)
        window.addView(title, titleParams)
        title.bringToFront()

        val close = terminalTab("X", minWidthDp = 48)
        val closeParams = FrameLayout.LayoutParams(dp(48), dp(36), Gravity.TOP or Gravity.END)
        closeParams.topMargin = dp(8)
        window.addView(close, closeParams)
        close.bringToFront()
        close.setOnClickListener { finish() }
        bindPanelCutouts(windowBorder, title, close)

        val outputFrame = FrameLayout(this)
        outputFrame.clipToPadding = false
        outputFrame.clipChildren = true
        outputFrame.setPadding(dp(8), dp(8), dp(8), dp(8))
        outputFrame.background = panelDrawable(
            fill = theme.outputBg,
            stroke = withAlpha(theme.outputBorder, 210),
            strokeDp = 1.2f,
            radiusDp = theme.outputCornerRadiusDp,
            notch = true
        )
        windowContent.addView(outputFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.TRANSPARENT)
        scroll.isFillViewport = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isVerticalScrollBarEnabled = false
        val scrollParams = FrameLayout.LayoutParams(-1, -1)
        scrollParams.topMargin = dp(48)
        outputFrame.addView(scroll, scrollParams)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.clipToPadding = false
        list.setPadding(0, dp(4), 0, dp(14))
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))

        val settingsLabel = terminalTab("SETTINGS", minWidthDp = 104, small = true)
        val settingsLabelParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        settingsLabelParams.leftMargin = dp(12)
        settingsLabelParams.topMargin = 0
        outputFrame.addView(settingsLabel, settingsLabelParams)
        settingsLabel.bringToFront()
        bindPanelCutouts(outputFrame, settingsLabel)

        addSectionLabel(list, "LAYOUT")
        addTerminalToggle(
            parent = list,
            label = getString(R.string.setting_show_number_row),
            summary = getString(R.string.setting_show_number_row_summary),
            key = KeyboardPrefs.KEY_SHOW_NUMBER_ROW,
            defaultValue = KeyboardPrefs.DEFAULT_SHOW_NUMBER_ROW
        )
        addTerminalToggle(
            parent = list,
            label = getString(R.string.setting_show_arrow_row),
            summary = getString(R.string.setting_show_arrow_row_summary),
            key = KeyboardPrefs.KEY_SHOW_ARROW_ROW,
            defaultValue = KeyboardPrefs.DEFAULT_SHOW_ARROW_ROW
        )
        addTerminalToggle(
            parent = list,
            label = getString(R.string.setting_quick_period),
            summary = getString(R.string.setting_quick_period_summary),
            key = KeyboardPrefs.KEY_QUICK_PERIOD,
            defaultValue = KeyboardPrefs.DEFAULT_QUICK_PERIOD
        )

        addSectionLabel(list, "SIZE")
        addTerminalControl(
            parent = list,
            label = getString(R.string.setting_keyboard_height),
            key = KeyboardPrefs.KEY_HEIGHT_PERCENT,
            min = 80,
            max = 180,
            defaultValue = KeyboardPrefs.DEFAULT_HEIGHT_PERCENT,
            suffix = "%"
        )
        addTerminalControl(
            parent = list,
            label = getString(R.string.setting_bottom_margin),
            key = KeyboardPrefs.KEY_BOTTOM_MARGIN_DP,
            min = 0,
            max = 64,
            defaultValue = KeyboardPrefs.DEFAULT_BOTTOM_MARGIN_DP,
            suffix = "dp"
        )
        addTerminalControl(
            parent = list,
            label = getString(R.string.setting_horizontal_margins),
            key = KeyboardPrefs.KEY_HORIZONTAL_MARGIN_DP,
            min = 0,
            max = 48,
            defaultValue = KeyboardPrefs.DEFAULT_HORIZONTAL_MARGIN_DP,
            suffix = "dp"
        )
        addTerminalControl(
            parent = list,
            label = getString(R.string.setting_key_gap),
            key = KeyboardPrefs.KEY_KEY_GAP_DP,
            min = 0,
            max = 8,
            defaultValue = KeyboardPrefs.DEFAULT_KEY_GAP_DP,
            suffix = "dp"
        )
        addTerminalControl(
            parent = list,
            label = getString(R.string.setting_corner_radius),
            key = KeyboardPrefs.KEY_CORNER_RADIUS_DP,
            min = 0,
            max = 18,
            defaultValue = KeyboardPrefs.DEFAULT_CORNER_RADIUS_DP,
            suffix = "dp"
        )
        addTerminalControl(
            parent = list,
            label = getString(R.string.setting_stroke_width),
            key = KeyboardPrefs.KEY_STROKE_WIDTH_DP,
            min = 0,
            max = 5,
            defaultValue = KeyboardPrefs.DEFAULT_STROKE_WIDTH_DP,
            suffix = "dp"
        )
        addTerminalControl(
            parent = list,
            label = getString(R.string.setting_background_opacity),
            key = KeyboardPrefs.KEY_BACKGROUND_IMAGE_OPACITY,
            min = 0,
            max = 100,
            defaultValue = KeyboardPrefs.DEFAULT_BACKGROUND_IMAGE_OPACITY,
            suffix = "%"
        )

        addSectionLabel(list, "TYPING")
        addTerminalToggle(
            parent = list,
            label = getString(R.string.setting_vibrate_on_keypress),
            summary = getString(R.string.setting_vibrate_on_keypress_summary),
            key = KeyboardPrefs.KEY_VIBRATE_ON_KEYPRESS,
            defaultValue = KeyboardPrefs.DEFAULT_VIBRATE_ON_KEYPRESS
        )
        addTerminalToggle(
            parent = list,
            label = getString(R.string.setting_sound_on_keypress),
            summary = getString(R.string.setting_sound_on_keypress_summary),
            key = KeyboardPrefs.KEY_SOUND_ON_KEYPRESS,
            defaultValue = KeyboardPrefs.DEFAULT_SOUND_ON_KEYPRESS
        )
        addTerminalToggle(
            parent = list,
            label = getString(R.string.setting_local_suggestions),
            summary = getString(R.string.setting_local_suggestions_summary),
            key = KeyboardPrefs.KEY_LOCAL_SUGGESTIONS,
            defaultValue = KeyboardPrefs.DEFAULT_LOCAL_SUGGESTIONS
        )
        addTerminalToggle(
            parent = list,
            label = getString(R.string.setting_learn_local_words),
            summary = getString(R.string.setting_learn_local_words_summary),
            key = KeyboardPrefs.KEY_LEARN_LOCAL_WORDS,
            defaultValue = KeyboardPrefs.DEFAULT_LEARN_LOCAL_WORDS
        )

        addSectionLabel(list, "DICTIONARY")
        addDictionaryControls(list)

        addSectionLabel(list, "THEME")
        addThemeColorControls(list)

        val imageLabel = terminalLabel("BACKGROUND: ${backgroundLabel()}", 12f, bold = true)
        imageLabel.setTextColor(theme.accent)
        val imageParams = LinearLayout.LayoutParams(-1, -2)
        imageParams.setMargins(0, dp(8), 0, dp(5))
        list.addView(imageLabel, imageParams)

        addCommandButton(list, getString(R.string.setting_pick_background)) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(intent, REQUEST_BACKGROUND)
        }

        addCommandButton(list, getString(R.string.setting_clear_background)) {
            prefs.edit().remove(KeyboardPrefs.KEY_BACKGROUND_IMAGE_URI).apply()
            rebuildRows()
            refreshKeyboard()
        }

        addCommandButton(list, getString(R.string.setting_input_methods)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        addCommandButton(list, getString(R.string.setting_reset_layout)) {
            val editor = prefs.edit()
            KeyboardPrefs.resetLayout(editor)
            editor.apply()
            rebuildRows()
            refreshKeyboard()
        }
    }

    private fun addSectionLabel(parent: LinearLayout, label: String) {
        val section = terminalLabel(label.uppercase(Locale.US), 12f, bold = true)
        section.setTextColor(theme.accent)
        section.setPadding(dp(4), dp(10), dp(4), dp(5))
        val params = LinearLayout.LayoutParams(-1, dp(34))
        params.setMargins(0, dp(2), 0, dp(1))
        parent.addView(section, params)
    }

    private fun addTerminalToggle(
        parent: LinearLayout,
        label: String,
        summary: String,
        key: String,
        defaultValue: Boolean
    ) {
        var enabled = prefs.getBoolean(key, defaultValue)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(7), dp(8), dp(7))
        row.isClickable = true
        row.isFocusable = true
        row.background = panelDrawable(
            fill = theme.rowBg,
            stroke = withAlpha(theme.border, 170),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        val rowParams = LinearLayout.LayoutParams(-1, -2)
        rowParams.setMargins(0, 0, 0, dp(7))
        parent.addView(row, rowParams)

        val copy = LinearLayout(this)
        copy.orientation = LinearLayout.VERTICAL
        copy.gravity = Gravity.CENTER_VERTICAL
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))

        val title = terminalLabel(
            label.uppercase(Locale.US),
            max(10f, theme.moduleBodyTextSizeSp.toFloat() - 2f),
            bold = true
        )
        copy.addView(title, LinearLayout.LayoutParams(-1, dp(22)))

        val description = terminalLabel(summary, max(9f, theme.moduleBodyTextSizeSp.toFloat() - 4f), bold = false)
        description.setTextColor(theme.dim)
        description.maxLines = 2
        copy.addView(description, LinearLayout.LayoutParams(-1, -2))

        val toggle = terminalToggleChip(enabled)
        val toggleParams = LinearLayout.LayoutParams(dp(66), dp(36))
        toggleParams.leftMargin = dp(10)
        row.addView(toggle, toggleParams)

        fun render(next: Boolean) {
            enabled = next
            toggle.text = if (enabled) "ON" else "OFF"
            toggle.setTextColor(if (enabled) theme.bg else theme.dim)
            toggle.background = panelDrawable(
                fill = if (enabled) theme.accent else theme.inputBg,
                stroke = if (enabled) theme.accent else withAlpha(theme.inputBorder, 160),
                strokeDp = 1f,
                radiusDp = theme.headerCornerRadiusDp.coerceAtLeast(theme.moduleCornerRadiusDp),
                notch = false
            )
        }

        row.setOnClickListener {
            val next = !enabled
            prefs.edit().putBoolean(key, next).apply()
            render(next)
            refreshKeyboard()
        }
        render(enabled)
    }

    private fun addTerminalControl(
        parent: LinearLayout,
        label: String,
        key: String,
        min: Int,
        max: Int,
        defaultValue: Int,
        suffix: String,
        step: Int = 10
    ) {
        var value = prefs.getInt(key, defaultValue).coerceIn(min, max)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(8), dp(7), dp(8), dp(7))
        row.background = panelDrawable(
            fill = theme.rowBg,
            stroke = withAlpha(theme.border, 170),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        val rowParams = LinearLayout.LayoutParams(-1, -2)
        rowParams.setMargins(0, 0, 0, dp(7))
        parent.addView(row, rowParams)

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val header = terminalLabel(
            label.uppercase(Locale.US),
            max(10f, theme.moduleBodyTextSizeSp.toFloat() - 2f),
            bold = true
        )
        headerRow.addView(header, LinearLayout.LayoutParams(0, dp(34), 1f))

        val valueInput = terminalValueInput(value)
        headerRow.addView(valueInput, LinearLayout.LayoutParams(dp(58), dp(34)))

        val suffixView = terminalLabel(suffix, 11f, bold = true)
        suffixView.gravity = Gravity.CENTER_VERTICAL
        suffixView.setTextColor(theme.dim)
        suffixView.setPadding(dp(6), 0, 0, 0)
        headerRow.addView(suffixView, LinearLayout.LayoutParams(dp(34), dp(34)))
        row.addView(headerRow, LinearLayout.LayoutParams(-1, dp(34)))

        val controlRow = LinearLayout(this)
        controlRow.orientation = LinearLayout.HORIZONTAL
        controlRow.gravity = Gravity.CENTER_VERTICAL

        val minus = terminalMicroButton("-")
        val plus = terminalMicroButton("+")
        val bar = terminalBar(value, min, max)

        fun updateViews(next: Int, syncField: Boolean) {
            value = next.coerceIn(min, max)
            bar.text = glyphBar(value, min, max)
            if (syncField && valueInput.text.toString() != value.toString()) {
                valueInput.setText(value.toString())
                valueInput.setSelection(valueInput.text.length)
            }
        }

        fun persist(nextRaw: Int, syncField: Boolean, focusPreview: Boolean) {
            val next = nextRaw.coerceIn(min, max)
            prefs.edit().putInt(key, next).apply()
            if (focusPreview) {
                valueInput.clearFocus()
                previewInput.requestFocus()
            }
            updateViews(next, syncField)
            refreshKeyboard()
        }

        fun persistManual() {
            val parsed = valueInput.text.toString().trim().toIntOrNull()
            if (parsed == null) {
                updateViews(prefs.getInt(key, defaultValue).coerceIn(min, max), syncField = true)
                return
            }
            persist(parsed, syncField = true, focusPreview = false)
        }

        minus.setOnClickListener { persist(value - step.coerceAtLeast(1), syncField = true, focusPreview = true) }
        plus.setOnClickListener { persist(value + step.coerceAtLeast(1), syncField = true, focusPreview = true) }
        valueInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                persistManual()
                true
            } else {
                false
            }
        }
        valueInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistManual()
        }

        val buttonParams = LinearLayout.LayoutParams(dp(38), dp(36))
        controlRow.addView(minus, buttonParams)
        val barParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        barParams.setMargins(dp(8), 0, dp(8), 0)
        controlRow.addView(bar, barParams)
        controlRow.addView(plus, LinearLayout.LayoutParams(dp(38), dp(36)))
        row.addView(controlRow, LinearLayout.LayoutParams(-1, dp(40)))
    }

    private fun addThemeColorControls(parent: LinearLayout) {
        val source = when {
            prefs.getBoolean(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN, false) -> "COLOR SOURCE: KEYBOARD OVERRIDE"
            prefs.getBoolean(KeyboardPrefs.KEY_THEME_LAUNCHER_AVAILABLE, false) -> "COLOR SOURCE: RETUI LAUNCHER"
            else -> "COLOR SOURCE: KEYBOARD DEFAULT"
        }
        val sourceLabel = terminalLabel(source, 12f, bold = true)
        sourceLabel.setTextColor(theme.accent)
        val sourceParams = LinearLayout.LayoutParams(-1, dp(28))
        sourceParams.setMargins(0, dp(2), 0, dp(4))
        parent.addView(sourceLabel, sourceParams)

        addCommandButton(parent, getString(R.string.setting_sync_launcher_colors)) {
            syncColorsFromLauncher()
        }
        addCommandButton(parent, getString(R.string.setting_clear_keyboard_colors)) {
            clearColorOverride()
        }

        colorBindings().forEach { binding ->
            addThemeColorControl(parent, binding)
        }

        addCommandButton(parent, getString(R.string.setting_save_keyboard_colors)) {
            saveColorOverride(themeDraft)
        }
    }

    private fun addThemeColorControl(parent: LinearLayout, binding: ColorBinding) {
        var value = binding.get(themeDraft)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(8), dp(7), dp(8), dp(7))
        row.background = panelDrawable(
            fill = theme.rowBg,
            stroke = withAlpha(theme.border, 170),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        val rowParams = LinearLayout.LayoutParams(-1, -2)
        rowParams.setMargins(0, 0, 0, dp(7))
        parent.addView(row, rowParams)

        val title = terminalLabel(
            binding.label.uppercase(Locale.US),
            max(10f, theme.moduleBodyTextSizeSp.toFloat() - 2f),
            bold = true
        )
        row.addView(title, LinearLayout.LayoutParams(-1, dp(24)))

        val description = terminalLabel(binding.summary, max(9f, theme.moduleBodyTextSizeSp.toFloat() - 4f), bold = false)
        description.setTextColor(theme.dim)
        description.maxLines = 2
        row.addView(description, LinearLayout.LayoutParams(-1, -2))

        val valueRow = LinearLayout(this)
        valueRow.orientation = LinearLayout.HORIZONTAL
        valueRow.gravity = Gravity.CENTER_VERTICAL
        val valueRowParams = LinearLayout.LayoutParams(-1, dp(42))
        valueRowParams.setMargins(0, dp(8), 0, dp(2))
        row.addView(valueRow, valueRowParams)

        val swatch = View(this)
        valueRow.addView(swatch, LinearLayout.LayoutParams(dp(48), dp(34)))

        val hexInput = terminalHexInput(value)
        val hexParams = LinearLayout.LayoutParams(0, dp(34), 1f)
        hexParams.leftMargin = dp(8)
        valueRow.addView(hexInput, hexParams)

        val channelViews = LinkedHashMap<Char, Pair<SeekBar, TextView>>()

        fun updateChannelViews() {
            channelViews.forEach { (channel, pair) ->
                val channelValue = colorChannel(value, channel)
                val slider = pair.first
                val label = pair.second
                if (slider.progress != channelValue) slider.progress = channelValue
                label.text = "$channel ${channelValue.toString().padStart(3, '0')}"
            }
        }

        fun render(next: Int, syncField: Boolean) {
            value = next
            themeDraft = binding.set(themeDraft, value)
            swatch.background = ColorSwatchDrawable(value, theme.border)
            if (syncField) {
                val hex = colorHex(value)
                if (hexInput.text.toString() != hex) {
                    hexInput.setText(hex)
                    hexInput.setSelection(hexInput.text.length)
                }
            }
            updateChannelViews()
        }

        fun persistManualHex() {
            val parsed = parseColorValue(hexInput.text.toString())
            if (parsed == null) {
                render(value, syncField = true)
                toast("INVALID COLOR")
            } else {
                render(parsed, syncField = true)
                hexInput.clearFocus()
                previewInput.requestFocus()
            }
        }

        listOf('A', 'R', 'G', 'B').forEach { channel ->
            val channelRow = LinearLayout(this)
            channelRow.orientation = LinearLayout.HORIZONTAL
            channelRow.gravity = Gravity.CENTER_VERTICAL
            val channelLabel = terminalLabel("$channel 000", 10f, bold = true)
            channelLabel.setTextColor(theme.dim)
            channelRow.addView(channelLabel, LinearLayout.LayoutParams(dp(54), dp(34)))

            val slider = SeekBar(this)
            slider.max = 255
            slider.progress = colorChannel(value, channel)
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) render(replaceColorChannel(value, channel, progress), syncField = true)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            channelRow.addView(slider, LinearLayout.LayoutParams(0, dp(34), 1f))
            channelViews[channel] = slider to channelLabel
            row.addView(channelRow, LinearLayout.LayoutParams(-1, dp(34)))
        }

        hexInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                persistManualHex()
                true
            } else {
                false
            }
        }
        hexInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistManualHex()
        }

        render(value, syncField = true)
    }

    private fun terminalHexInput(value: Int): EditText {
        val input = EditText(this)
        input.typeface = Typeface.MONOSPACE
        input.setSingleLine(true)
        input.setSelectAllOnFocus(true)
        input.gravity = Gravity.CENTER_VERTICAL
        input.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        input.setTextColor(theme.text)
        input.setHintTextColor(withAlpha(theme.dim, 150))
        input.textSize = max(10f, theme.moduleBodyTextSizeSp.toFloat() - 1f)
        input.setPadding(dp(10), 0, dp(10), 0)
        input.background = panelDrawable(
            fill = theme.inputBg,
            stroke = withAlpha(theme.inputBorder, 160),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        input.setText(colorHex(value))
        return input
    }

    private fun syncColorsFromLauncher() {
        if (!prefs.getBoolean(KeyboardPrefs.KEY_THEME_LAUNCHER_AVAILABLE, false)) {
            toast("OPEN RETUI LAUNCHER FIRST")
            return
        }
        val launcherTheme = deriveSettingsTheme(readSettingsThemeSnapshot(KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX, SettingsTheme.DEFAULT))
        saveSettingsTheme(launcherTheme, overrideColors = true)
        theme = launcherTheme
        themeDraft = launcherTheme
        toast("SYNCED LAUNCHER COLORS")
        configureWindow()
        rebuildRows()
        refreshKeyboard()
    }

    private fun saveColorOverride(next: SettingsTheme) {
        val saved = deriveSettingsTheme(next)
        saveSettingsTheme(saved, overrideColors = true)
        theme = saved
        themeDraft = saved
        toast("KEYBOARD COLORS SAVED")
        configureWindow()
        rebuildRows()
        refreshKeyboard()
    }

    private fun clearColorOverride() {
        prefs.edit().putBoolean(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN, false).apply()
        theme = loadSettingsTheme(null)
        themeDraft = theme
        toast("KEYBOARD COLOR OVERRIDE CLEARED")
        configureWindow()
        rebuildRows()
        refreshKeyboard()
    }

    private fun addDictionaryControls(parent: LinearLayout) {
        val words = LocalDictionary.userWords(prefs)
        val count = terminalLabel("LOCAL WORDS: ${words.size}", 12f, bold = true)
        count.setTextColor(theme.accent)
        val countParams = LinearLayout.LayoutParams(-1, dp(30))
        countParams.setMargins(0, dp(2), 0, dp(4))
        parent.addView(count, countParams)

        addDictionaryAddRow(parent)
        addCommandButton(parent, getString(R.string.setting_dictionary_edit)) {
            dictionaryEditorOpen = true
            rebuildRows()
        }
        if (dictionaryEditorOpen) {
            addDictionaryEditor(parent)
        }
        addCommandButton(parent, getString(R.string.setting_profile_backup)) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "application/json"
            intent.putExtra(Intent.EXTRA_TITLE, "retui-keyboard-profile.json")
            startActivityForResult(intent, REQUEST_PROFILE_BACKUP)
        }
        addCommandButton(parent, getString(R.string.setting_profile_restore)) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "application/json"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(intent, REQUEST_PROFILE_RESTORE)
        }
    }

    private fun addDictionaryAddRow(parent: LinearLayout) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(7), dp(8), dp(7))
        row.background = panelDrawable(
            fill = theme.rowBg,
            stroke = withAlpha(theme.border, 170),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        val rowParams = LinearLayout.LayoutParams(-1, dp(52))
        rowParams.setMargins(0, 0, 0, dp(7))
        parent.addView(row, rowParams)

        val input = terminalWordInput()
        row.addView(input, LinearLayout.LayoutParams(0, dp(36), 1f))

        val add = terminalMicroButton("+")
        val addParams = LinearLayout.LayoutParams(dp(42), dp(36))
        addParams.leftMargin = dp(8)
        row.addView(add, addParams)

        fun addWord() {
            val raw = input.text.toString()
            if (LocalDictionary.learnTypedWord(prefs, raw, force = true)) {
                input.setText("")
                toast("WORD ADDED")
                rebuildRows()
                refreshKeyboard()
            } else {
                toast("INVALID WORD")
            }
        }
        add.setOnClickListener { addWord() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addWord()
                true
            } else {
                false
            }
        }
    }

    private fun addDictionaryWordRow(parent: LinearLayout, entry: UserWordEntry) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(5), dp(8), dp(5))
        row.background = panelDrawable(
            fill = theme.rowBg,
            stroke = withAlpha(theme.border, 130),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        val rowParams = LinearLayout.LayoutParams(-1, dp(42))
        rowParams.setMargins(0, 0, 0, dp(5))
        parent.addView(row, rowParams)

        val word = terminalLabel(entry.word.uppercase(Locale.US), max(10f, theme.moduleBodyTextSizeSp.toFloat() - 2f), bold = true)
        row.addView(word, LinearLayout.LayoutParams(0, -1, 1f))

        val frequency = terminalLabel("x${entry.frequency}", max(9f, theme.moduleBodyTextSizeSp.toFloat() - 4f), bold = true)
        frequency.gravity = Gravity.CENTER
        frequency.setTextColor(theme.dim)
        row.addView(frequency, LinearLayout.LayoutParams(dp(44), -1))

        val remove = terminalMicroButton("X")
        remove.textSize = 12f
        val removeParams = LinearLayout.LayoutParams(dp(38), dp(32))
        removeParams.leftMargin = dp(8)
        row.addView(remove, removeParams)
        remove.setOnClickListener {
            LocalDictionary.removeWord(prefs, entry.word)
            toast("WORD REMOVED")
            rebuildRows()
            refreshKeyboard()
        }
    }

    private fun addDictionaryEditor(parent: LinearLayout) {
        val editor = terminalDictionaryEditor()
        editor.setText(LocalDictionary.editableText(prefs))
        val editorParams = LinearLayout.LayoutParams(-1, dp(178))
        editorParams.setMargins(0, 0, 0, dp(7))
        parent.addView(editor, editorParams)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        val actionsParams = LinearLayout.LayoutParams(-1, dp(44))
        actionsParams.setMargins(0, 0, 0, dp(7))
        parent.addView(actions, actionsParams)

        val save = terminalEditorButton("SAVE")
        val close = terminalEditorButton("CLOSE")
        val saveParams = LinearLayout.LayoutParams(0, -1, 1f)
        saveParams.rightMargin = dp(4)
        val closeParams = LinearLayout.LayoutParams(0, -1, 1f)
        closeParams.leftMargin = dp(4)
        actions.addView(save, saveParams)
        actions.addView(close, closeParams)

        save.setOnClickListener {
            val result = LocalDictionary.replaceUserWords(prefs, editor.text.toString())
            dictionaryEditorOpen = false
            toast("SAVED ${result.wordCount} WORDS")
            rebuildRows()
            refreshKeyboard()
        }
        close.setOnClickListener {
            dictionaryEditorOpen = false
            rebuildRows()
        }
    }

    private fun addCommandButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = TextView(this)
        button.text = label.uppercase(Locale.US)
        button.typeface = Typeface.MONOSPACE
        button.setTextColor(theme.text)
        button.textSize = max(10f, theme.moduleBodyTextSizeSp.toFloat() - 2f)
        button.gravity = Gravity.CENTER
        button.setIncludeFontPadding(false)
        button.isClickable = true
        button.isFocusable = true
        button.minHeight = dp(42)
        button.setPadding(dp(10), 0, dp(10), 0)
        button.background = panelDrawable(
            fill = theme.actionBg,
            stroke = theme.border,
            strokeDp = 1.1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        button.setOnClickListener { action() }
        val params = LinearLayout.LayoutParams(-1, dp(44))
        params.setMargins(0, dp(5), 0, dp(3))
        parent.addView(button, params)
    }

    private fun backgroundLabel(): String {
        val uri = prefs.getString(KeyboardPrefs.KEY_BACKGROUND_IMAGE_URI, null)
        if (uri.isNullOrBlank()) return getString(R.string.setting_no_background)
        return try {
            Uri.parse(uri).lastPathSegment ?: getString(R.string.setting_background_selected)
        } catch (_: Exception) {
            getString(R.string.setting_background_selected)
        }
    }

    private fun previewDockView(): LinearLayout {
        previewDock = LinearLayout(this)
        previewDock.orientation = LinearLayout.VERTICAL
        previewDock.clipChildren = false
        previewDock.clipToPadding = false
        previewDock.setPadding(dp(8), dp(14), dp(8), dp(8))
        previewDock.setBackgroundColor(theme.bg)

        val previewFrame = FrameLayout(this)
        previewFrame.clipChildren = false
        previewFrame.clipToPadding = false
        previewFrame.background = panelDrawable(
            fill = theme.inputBg,
            stroke = withAlpha(theme.inputBorder, 180),
            strokeDp = 1.2f,
            radiusDp = theme.outputCornerRadiusDp,
            notch = false
        )
        previewDock.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(52)))

        val previewLabel = terminalTab("PREVIEW", minWidthDp = 104, small = true)
        val labelParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        labelParams.leftMargin = dp(12)
        labelParams.topMargin = -dp(13)
        previewFrame.addView(previewLabel, labelParams)
        previewLabel.bringToFront()
        bindPanelCutouts(previewFrame, previewLabel)

        val group = LinearLayout(this)
        group.orientation = LinearLayout.HORIZONTAL
        group.gravity = Gravity.CENTER_VERTICAL
        group.setPadding(dp(10), dp(4), dp(10), dp(4))
        group.setBackgroundColor(Color.TRANSPARENT)
        val groupParams = FrameLayout.LayoutParams(-1, dp(38), Gravity.BOTTOM)
        groupParams.leftMargin = dp(8)
        groupParams.rightMargin = dp(8)
        groupParams.bottomMargin = dp(3)
        previewFrame.addView(group, groupParams)

        val prefix = terminalLabel("$", theme.moduleBodyTextSizeSp.toFloat(), bold = true)
        prefix.gravity = Gravity.CENTER
        group.addView(prefix, LinearLayout.LayoutParams(dp(18), -1))

        previewInput = EditText(this)
        previewInput.isFocusableInTouchMode = true
        previewInput.typeface = Typeface.MONOSPACE
        previewInput.setSingleLine(true)
        previewInput.hint = getString(R.string.setting_preview_hint)
        previewInput.setTextColor(theme.accent)
        previewInput.setHintTextColor(withAlpha(theme.accent, 150))
        previewInput.setBackgroundColor(Color.TRANSPARENT)
        previewInput.setPadding(dp(6), 0, dp(6), 0)
        previewInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        previewInput.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN
        group.addView(previewInput, LinearLayout.LayoutParams(0, -1, 1f))

        return previewDock
    }

    private fun terminalLabel(text: String, sizeSp: Float, bold: Boolean): TextView {
        val view = TextView(this)
        view.text = text
        view.typeface = Typeface.MONOSPACE
        view.setTextColor(theme.text)
        view.textSize = sizeSp
        view.gravity = Gravity.CENTER_VERTICAL
        view.setIncludeFontPadding(false)
        if (bold) {
            view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        return view
    }

    private fun terminalTab(text: String, minWidthDp: Int, small: Boolean = false): TextView {
        val size = if (small) {
            max(10f, theme.outputHeaderTextSizeSp.toFloat() - 2f)
        } else {
            theme.outputHeaderTextSizeSp.toFloat()
        }
        val view = terminalLabel(text, size, bold = true)
        view.gravity = Gravity.CENTER
        view.setTextColor(theme.headerText)
        view.minWidth = dp(minWidthDp)
        view.minHeight = if (small) dp(26) else dp(30)
        view.setPadding(dp(12), dp(2), dp(12), dp(2))
        view.background = tabDrawable(theme.headerBg)
        return view
    }

    private fun terminalValueInput(value: Int): EditText {
        val input = EditText(this)
        input.typeface = Typeface.MONOSPACE
        input.setSingleLine(true)
        input.setSelectAllOnFocus(true)
        input.gravity = Gravity.CENTER
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        input.setTextColor(theme.text)
        input.setHintTextColor(withAlpha(theme.dim, 150))
        input.textSize = max(10f, theme.moduleBodyTextSizeSp.toFloat() - 1f)
        input.setPadding(dp(2), 0, dp(2), 0)
        input.background = panelDrawable(
            fill = theme.inputBg,
            stroke = withAlpha(theme.inputBorder, 160),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        input.setText(value.toString())
        return input
    }

    private fun terminalWordInput(): EditText {
        val input = EditText(this)
        input.typeface = Typeface.MONOSPACE
        input.setSingleLine(true)
        input.hint = getString(R.string.setting_dictionary_add_hint).uppercase(Locale.US)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        input.setTextColor(theme.text)
        input.setHintTextColor(withAlpha(theme.dim, 150))
        input.textSize = max(10f, theme.moduleBodyTextSizeSp.toFloat() - 1f)
        input.setPadding(dp(10), 0, dp(10), 0)
        input.background = panelDrawable(
            fill = theme.inputBg,
            stroke = withAlpha(theme.inputBorder, 160),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        return input
    }

    private fun terminalDictionaryEditor(): EditText {
        val input = EditText(this)
        input.typeface = Typeface.MONOSPACE
        input.setSingleLine(false)
        input.minLines = 6
        input.gravity = Gravity.TOP or Gravity.START
        input.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        input.setTextColor(theme.text)
        input.setHintTextColor(withAlpha(theme.dim, 150))
        input.textSize = max(10f, theme.moduleBodyTextSizeSp.toFloat() - 1f)
        input.setPadding(dp(10), dp(8), dp(10), dp(8))
        input.background = panelDrawable(
            fill = theme.inputBg,
            stroke = withAlpha(theme.inputBorder, 160),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        return input
    }

    private fun terminalEditorButton(text: String): TextView {
        val button = terminalLabel(text, max(10f, theme.moduleBodyTextSizeSp.toFloat() - 2f), bold = true)
        button.gravity = Gravity.CENTER
        button.isClickable = true
        button.isFocusable = true
        button.background = panelDrawable(
            fill = theme.actionBg,
            stroke = theme.border,
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        return button
    }

    private fun terminalMicroButton(text: String): TextView {
        val button = terminalLabel(text, 18f, bold = true)
        button.gravity = Gravity.CENTER
        button.isClickable = true
        button.isFocusable = true
        button.background = panelDrawable(
            fill = theme.headerBg,
            stroke = theme.border,
            strokeDp = 1f,
            radiusDp = theme.headerCornerRadiusDp,
            notch = false
        )
        return button
    }

    private fun terminalToggleChip(enabled: Boolean): TextView {
        val chip = terminalLabel(if (enabled) "ON" else "OFF", 12f, bold = true)
        chip.gravity = Gravity.CENTER
        chip.setPadding(dp(8), 0, dp(8), 0)
        chip.isClickable = false
        chip.isFocusable = false
        return chip
    }

    private fun backupProfile(uri: Uri) {
        val written = try {
            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                false
            } else {
                stream.use {
                    OutputStreamWriter(it, Charsets.UTF_8).use { writer ->
                        writer.write(KeyboardProfileBackup.exportJson(prefs))
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
        toast(if (written) "PROFILE BACKED UP" else "BACKUP FAILED")
    }

    private fun restoreProfile(uri: Uri) {
        val result = try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (raw.isNullOrBlank()) {
                null
            } else {
                KeyboardProfileBackup.importJson(prefs, raw)
            }
        } catch (_: Exception) {
            null
        }
        if (result == null) {
            toast("RESTORE FAILED")
            return
        }
        dictionaryEditorOpen = false
        theme = loadSettingsTheme(null)
        themeDraft = theme
        toast("RESTORED ${result.wordCount} WORDS / ${result.preferenceCount} SETTINGS")
        rebuildRows()
        refreshKeyboard()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun terminalBar(value: Int, min: Int, max: Int): TextView {
        val bar = terminalLabel(glyphBar(value, min, max), max(11f, theme.moduleBodyTextSizeSp.toFloat()), bold = true)
        bar.gravity = Gravity.CENTER
        bar.setTextColor(theme.accent)
        bar.isClickable = false
        bar.isFocusable = false
        bar.background = panelDrawable(
            fill = theme.inputBg,
            stroke = withAlpha(theme.inputBorder, 140),
            strokeDp = 1f,
            radiusDp = theme.moduleCornerRadiusDp,
            notch = false
        )
        return bar
    }

    private fun glyphBar(value: Int, min: Int, max: Int): String {
        val clamped = value.coerceIn(min, max)
        val ratio = if (max == min) 1f else (clamped - min).toFloat() / (max - min).toFloat()
        val filled = (ratio * BAR_SEGMENTS).roundToInt().coerceIn(0, BAR_SEGMENTS)
        val out = StringBuilder("[")
        repeat(filled) { out.append('█') }
        repeat(BAR_SEGMENTS - filled) { out.append('░') }
        return out.append(']').toString()
    }

    private fun refreshKeyboard() {
        if (!::previewInput.isInitialized) return
        sendPreviewTheme()
        if (currentFocus !is EditText || currentFocus == previewInput) {
            showPreviewKeyboard(delayMs = 80L)
        }
    }

    private fun showPreviewKeyboard(delayMs: Long = 250L) {
        if (!::previewInput.isInitialized) return
        previewInput.postDelayed({
            previewInput.requestFocus()
            sendPreviewTheme()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(previewInput, InputMethodManager.SHOW_FORCED)
            previewInput.postDelayed({ sendPreviewTheme() }, 160L)
        }, delayMs)
    }

    private fun sendPreviewTheme() {
        if (!::previewInput.isInitialized) return
        val data = Bundle().apply {
            putInt("theme_bg", theme.bg)
            putInt("theme_text", theme.text)
            putInt("terminal_border_color", theme.border)
            putInt("terminal_window_background_color", theme.panelBg)
            putInt("terminal_header_background_color", theme.headerBg)
            putInt("terminal_header_border_color", theme.headerTabBorder)
            putInt("module_text_color", theme.headerText)
            putInt("module_button_background_color", theme.inputBg)
            putInt("module_button_text_color", theme.inputText)
            putInt("output_background_color", theme.outputBg)
            putInt("output_border_color", theme.outputBorder)
            putBoolean("enable_dashed_border", theme.dashedBorders)
            putInt("dashed_border_dash_length", theme.dashLengthDp)
            putInt("dashed_border_gap_length", theme.dashGapDp)
            putString("dashed_border_stroke_width", theme.dashedStrokeWidthDp.toString())
            putInt("module_corner_radius", theme.moduleCornerRadiusDp)
            putInt("output_corner_radius", theme.outputCornerRadiusDp)
            putInt("header_corner_radius", theme.headerCornerRadiusDp)
            putInt("module_body_text_size", theme.moduleBodyTextSizeSp)
            putInt("output_header_text_size", theme.outputHeaderTextSizeSp)
            putBoolean("enable_cyberdeck_mode", theme.cyberdeckMode)
            putBoolean("enable_crt_filter", theme.crtFilter)
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.sendAppPrivateCommand(previewInput, RetuiKeyboardService.ACTION_APPLY_THEME, data)
    }

    private fun applySystemInsets(insets: WindowInsets) {
        topInsetPx = systemTopInset(insets)
        bottomInsetPx = systemBottomInset(insets)
        imeInsetPx = imeBottomInset(insets)
        updateSurfaceMargins()
    }

    private fun systemTopInset(insets: WindowInsets): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }
    }

    private fun systemBottomInset(insets: WindowInsets): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
    }

    private fun imeBottomInset(insets: WindowInsets): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            0
        }
    }

    private fun updateSurfaceMargins() {
        if (::settingsFrame.isInitialized) {
            val params = settingsFrame.layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                val dockHeight = if (::previewDock.isInitialized && previewDock.measuredHeight > 0) {
                    previewDock.measuredHeight
                } else {
                    dp(78)
                }
                val topMargin = dp(22) + topInsetPx
                val rootHeight = (settingsFrame.parent as? View)?.height ?: resources.displayMetrics.heightPixels
                val fullBottomMargin = dockHeight + imeInsetPx + dp(4)
                val reservesDock = rootHeight - topMargin - fullBottomMargin >= dp(220)
                params.setMargins(
                    dp(22),
                    topMargin,
                    dp(22),
                    if (reservesDock) fullBottomMargin else imeInsetPx + dp(4)
                )
                settingsFrame.layoutParams = params
            }
        }
        if (::previewDock.isInitialized) {
            previewDock.setPadding(dp(22), dp(2), dp(22), dp(4) + bottomInsetPx)
            previewDock.translationY = -imeInsetPx.toFloat()
        }
    }

    private fun panelDrawable(
        fill: Int,
        stroke: Int,
        strokeDp: Float,
        notch: Boolean,
        radiusDp: Int = theme.moduleCornerRadiusDp
    ): Drawable {
        val strokeWidthPx = when {
            theme.cyberdeckMode -> max(1, dpFloat(strokeDp).roundToInt())
            Color.alpha(stroke) == 0 -> 0
            theme.dashedBorders -> max(
                1,
                dpFloat(clampStrokeWidth(theme.dashedStrokeWidthDp * (strokeDp / 1.5f))).roundToInt()
            )
            else -> 0
        }
        return KeyboardTerminalBorderDrawable(
            fillColor = fill,
            borderColor = stroke,
            strokeWidthPx = strokeWidthPx,
            radiusPx = if (theme.cyberdeckMode) 0f else dpFloat(radiusDp.coerceIn(0, 48).toFloat()),
            dashed = theme.dashedBorders && !theme.cyberdeckMode,
            dashLengthPx = dpFloat(theme.dashLengthDp.coerceAtLeast(0).toFloat()),
            dashGapPx = dpFloat(theme.dashGapDp.coerceAtLeast(0).toFloat()),
            cyberdeck = theme.cyberdeckMode,
            cyberdeckNotch = notch
        )
    }

    private fun tabDrawable(fill: Int): Drawable {
        if (theme.cyberdeckMode) {
            return KeyboardTerminalBorderDrawable(
                fillColor = fill,
                borderColor = theme.headerTabBorder,
                strokeWidthPx = max(1, dpFloat(1.2f).roundToInt()),
                radiusPx = 0f,
                dashed = false,
                dashLengthPx = 0f,
                dashGapPx = 0f,
                cyberdeck = true,
                cyberdeckNotch = true
            )
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dpFloat(theme.headerCornerRadiusDp.coerceIn(0, 48).toFloat())
            if (theme.dashedBorders && Color.alpha(theme.headerTabBorder) > 0) {
                val stroke = max(1, dpFloat(clampStrokeWidth(theme.dashedStrokeWidthDp)).roundToInt())
                val dashLength = dpFloat(theme.dashLengthDp.coerceAtLeast(0).toFloat())
                val dashGap = dpFloat(theme.dashGapDp.coerceAtLeast(0).toFloat())
                if (dashLength > 0f && dashGap > 0f) {
                    setStroke(stroke, theme.headerTabBorder, dashLength, dashGap)
                } else {
                    setStroke(stroke, theme.headerTabBorder)
                }
            } else {
                setStroke(0, Color.TRANSPARENT)
            }
        }
    }

    private fun bindPanelCutouts(panel: View?, vararg cutoutViews: View?) {
        val border = panel ?: return
        val drawable = border.background as? KeyboardTerminalBorderDrawable ?: return
        val views = cutoutViews.filterNotNull()
        if (views.isEmpty()) {
            drawable.setCutouts(emptyList(), emptyList())
            return
        }
        val runnable = CutoutRunnable(border, drawable, views)
        border.post(runnable)
        for (view in views) {
            view.post(runnable)
        }
    }

    private inner class CutoutRunnable(
        private val panel: View,
        private val drawable: KeyboardTerminalBorderDrawable,
        private val cutoutViews: List<View>
    ) : Runnable {
        override fun run() {
            if (panel.width <= 0 || panel.height <= 0) return
            val panelLocation = IntArray(2)
            panel.getLocationOnScreen(panelLocation)
            val gutter = dp(6)
            val overlapSlop = dp(12)
            val cutoutHeight = max(drawable.strokeWidthPx * 4, dp(10)).toFloat()
            val topOut = ArrayList<RectF>()
            val bottomOut = ArrayList<RectF>()

            for (view in cutoutViews) {
                if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue
                val childLocation = IntArray(2)
                view.getLocationOnScreen(childLocation)
                val relativeTop = childLocation[1] - panelLocation[1]
                val relativeBottom = relativeTop + view.height
                val left = childLocation[0] - panelLocation[0] - gutter
                val right = childLocation[0] - panelLocation[0] + view.width + gutter
                val cutout = RectF(
                    max(0, left).toFloat(),
                    0f,
                    min(panel.width, right).toFloat(),
                    cutoutHeight
                )
                if (relativeBottom >= -overlapSlop && relativeTop <= overlapSlop) {
                    topOut.add(cutout)
                } else if (relativeTop <= panel.height + overlapSlop && relativeBottom >= panel.height - overlapSlop) {
                    bottomOut.add(RectF(cutout.left, 0f, cutout.right, cutoutHeight))
                }
            }
            drawable.setCutouts(topOut, bottomOut)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dpFloat(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun loadSettingsTheme(intent: Intent?): SettingsTheme {
        var next = activeSettingsTheme()
        intent?.extras?.let { extras ->
            val launcherTheme = applyThemeBundle(
                readSettingsThemeSnapshot(KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX, SettingsTheme.DEFAULT),
                extras
            )
            if (launcherTheme != readSettingsThemeSnapshot(KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX, SettingsTheme.DEFAULT)) {
                saveLauncherTheme(launcherTheme)
            }
            if (!prefs.getBoolean(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN, false)) {
                next = launcherTheme
            }
        }
        return deriveSettingsTheme(next)
    }

    private fun activeSettingsTheme(): SettingsTheme {
        return when {
            prefs.getBoolean(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN, false) ->
                readSettingsThemeSnapshot(THEME_OVERRIDE_PREFIX, SettingsTheme.DEFAULT)
            prefs.getBoolean(KeyboardPrefs.KEY_THEME_LAUNCHER_AVAILABLE, false) ->
                readSettingsThemeSnapshot(KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX, SettingsTheme.DEFAULT)
            else -> SettingsTheme.DEFAULT
        }
    }

    private fun applyThemeBundle(base: SettingsTheme, bundle: Bundle): SettingsTheme {
        return base.copy(
            bg = readColor(bundle, base.bg, "theme_bg", "background_color", "theme_background_color"),
            text = readColor(bundle, base.text, "theme_text", "output_text_color", "theme_text_color", "module_text_color"),
            border = readColor(bundle, base.border, "theme_border", "terminal_border_color", "module_border_color"),
            panelBg = readColor(bundle, base.panelBg, "terminal_bg", "terminal_window_background_color", "module_bg_color"),
            headerBg = readColor(
                bundle,
                base.headerBg,
                "terminal_header_background_color",
                "terminal_header_tab_background_color",
                "module_header_bg_color"
            ),
            headerTabBorder = readColor(
                bundle,
                base.headerTabBorder,
                "terminal_header_border_color",
                "terminal_header_tab_border_color",
                "header_tab_border_color"
            ),
            headerText = readColor(
                bundle,
                base.headerText,
                "module_text_color",
                "module_header_text_color",
                "terminal_header_text_color",
                "notification_widget_text_color"
            ),
            inputBg = readColor(
                bundle,
                base.inputBg,
                "module_button_background_color",
                "module_button_bg_color",
                "input_bg_color",
                "input_background_color"
            ),
            inputText = readColor(
                bundle,
                base.inputText,
                "module_button_text_color",
                "module_text_color",
                "input_text_color",
                "input_text"
            ),
            outputBg = readColor(bundle, base.outputBg, "output_bg_color", "output_background_color", "output_bg"),
            outputBorder = readColor(bundle, base.outputBorder, "output_border_color", "output_border", "terminal_border_color"),
            dashedBorders = readBoolean(
                bundle,
                base.dashedBorders,
                "enable_dashed_border",
                "dashed_borders",
                "dashed_border",
                "terminal_dashed_borders"
            ) ?: base.dashedBorders,
            dashLengthDp = readInt(
                bundle,
                base.dashLengthDp,
                "dashed_border_dash_length",
                "dash_length",
                "terminal_dash_length"
            ),
            dashGapDp = readInt(
                bundle,
                base.dashGapDp,
                "dashed_border_gap_length",
                "dash_gap",
                "terminal_dash_gap"
            ),
            dashedStrokeWidthDp = readFloat(
                bundle,
                base.dashedStrokeWidthDp,
                "dashed_border_stroke_width",
                "dashed_border_stroke_width_dp",
                "dash_stroke_width"
            ),
            moduleCornerRadiusDp = readInt(
                bundle,
                base.moduleCornerRadiusDp,
                "module_corner_radius",
                "module_corner_radius_dp",
                "corner_radius",
                "corner_radius_dp"
            ),
            outputCornerRadiusDp = readInt(
                bundle,
                base.outputCornerRadiusDp,
                "output_corner_radius",
                "output_corner_radius_dp",
                "terminal_corner_radius"
            ),
            headerCornerRadiusDp = readInt(
                bundle,
                base.headerCornerRadiusDp,
                "header_corner_radius",
                "header_corner_radius_dp",
                "terminal_header_corner_radius"
            ),
            moduleBodyTextSizeSp = readInt(
                bundle,
                base.moduleBodyTextSizeSp,
                "module_body_text_size",
                "module_body_text_size_sp",
                "module_output_text_size",
                "output_font_size"
            ),
            outputHeaderTextSizeSp = readInt(
                bundle,
                base.outputHeaderTextSizeSp,
                "output_header_text_size",
                "output_header_text_size_sp",
                "module_header_text_size",
                "module_header_text_size_sp",
                "header_font_size"
            ),
            cyberdeckMode = readBoolean(
                bundle,
                base.cyberdeckMode,
                "enable_cyberdeck_mode",
                "cyberdeck_mode",
                "cyberdeck",
                "enable_cyberdeck"
            ) ?: base.cyberdeckMode,
            crtFilter = readBoolean(
                bundle,
                base.crtFilter,
                "enable_crt_filter",
                "crt_filter",
                "crt",
                "enable_crt"
            ) ?: base.crtFilter
        )
    }

    private fun deriveSettingsTheme(base: SettingsTheme): SettingsTheme {
        val outputBg = if (base.outputBg != 0) base.outputBg else blendColor(base.panelBg, Color.BLACK, 0.10f)
        val outputBorder = if (base.outputBorder != 0) base.outputBorder else base.border
        val inputBg = if (base.inputBg != 0) base.inputBg else blendColor(base.panelBg, Color.BLACK, 0.16f)
        val inputText = if (base.inputText != 0) base.inputText else base.text
        return base.copy(
            outputBg = outputBg,
            outputBorder = outputBorder,
            inputBg = inputBg,
            inputBorder = outputBorder,
            rowBg = blendColor(outputBg, Color.BLACK, 0.08f),
            actionBg = blendColor(inputBg, Color.WHITE, 0.06f),
            accent = inputText,
            dim = blendColor(inputText, base.bg, 0.42f),
            dashLengthDp = base.dashLengthDp.coerceIn(0, 48),
            dashGapDp = base.dashGapDp.coerceIn(0, 48),
            dashedStrokeWidthDp = clampStrokeWidth(base.dashedStrokeWidthDp),
            moduleCornerRadiusDp = base.moduleCornerRadiusDp.coerceIn(0, 48),
            outputCornerRadiusDp = base.outputCornerRadiusDp.coerceIn(0, 48),
            headerCornerRadiusDp = base.headerCornerRadiusDp.coerceIn(0, 48),
            moduleBodyTextSizeSp = clampTextSize(base.moduleBodyTextSizeSp),
            outputHeaderTextSizeSp = clampTextSize(base.outputHeaderTextSizeSp)
        )
    }

    private fun readSettingsThemeSnapshot(prefix: String, fallback: SettingsTheme): SettingsTheme {
        return fallback.copy(
            bg = prefs.getInt(prefix + "bg", fallback.bg),
            text = prefs.getInt(prefix + "text", fallback.text),
            border = prefs.getInt(prefix + "border", fallback.border),
            panelBg = prefs.getInt(prefix + "panelBg", fallback.panelBg),
            headerBg = prefs.getInt(prefix + "headerBg", fallback.headerBg),
            headerTabBorder = prefs.getInt(prefix + "headerTabBorder", fallback.headerTabBorder),
            headerText = prefs.getInt(prefix + "headerText", fallback.headerText),
            inputBg = prefs.getInt(prefix + "keyBg", fallback.inputBg),
            inputText = prefs.getInt(prefix + "keyText", fallback.inputText),
            outputBg = prefs.getInt(prefix + "outputBg", fallback.outputBg),
            outputBorder = prefs.getInt(prefix + "outputBorder", fallback.outputBorder),
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

    private fun saveSettingsTheme(next: SettingsTheme, overrideColors: Boolean) {
        saveSettingsThemeSnapshot(next, THEME_OVERRIDE_PREFIX, overrideColors)
    }

    private fun saveLauncherTheme(next: SettingsTheme) {
        saveSettingsThemeSnapshot(next, KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX, overrideColors = false)
    }

    private fun saveSettingsThemeSnapshot(next: SettingsTheme, prefix: String, overrideColors: Boolean) {
        val editor = prefs.edit()
            .putInt(prefix + "bg", next.bg)
            .putInt(prefix + "text", next.text)
            .putInt(prefix + "border", next.border)
            .putInt(prefix + "panelBg", next.panelBg)
            .putInt(prefix + "headerBg", next.headerBg)
            .putInt(prefix + "headerTabBorder", next.headerTabBorder)
            .putInt(prefix + "headerText", next.headerText)
            .putInt(prefix + "keyBg", next.inputBg)
            .putInt(prefix + "keyText", next.inputText)
            .putInt(prefix + "outputBg", next.outputBg)
            .putInt(prefix + "outputBorder", next.outputBorder)
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
        if (overrideColors) {
            editor.putBoolean(KeyboardPrefs.KEY_THEME_COLORS_OVERRIDDEN, true)
        }
        if (prefix == KeyboardPrefs.KEY_THEME_LAUNCHER_PREFIX) {
            editor
                .putBoolean(KeyboardPrefs.KEY_THEME_LAUNCHER_AVAILABLE, true)
                .putLong(KeyboardPrefs.KEY_THEME_LAUNCHER_UPDATED_AT, System.currentTimeMillis())
        }
        editor.apply()
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

    private fun blendColor(from: Int, to: Int, amount: Float): Int {
        val clamped = amount.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * clamped).roundToInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped).roundToInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped).roundToInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped).roundToInt()
        return Color.argb(a, r, g, b)
    }

    private fun clampStrokeWidth(width: Float): Float {
        if (width.isNaN() || width.isInfinite()) return 1.5f
        return width.coerceIn(0.5f, 8f)
    }

    private fun clampTextSize(size: Int): Int {
        return size.coerceIn(8, 32)
    }

    private fun colorBindings(): List<ColorBinding> {
        return listOf(
            ColorBinding(
                label = "Keyboard background",
                summary = "theme.xml: background_color / theme_bg",
                get = { it.bg },
                set = { state, color -> state.copy(bg = color) }
            ),
            ColorBinding(
                label = "Primary text",
                summary = "theme.xml: output_text_color / theme_text",
                get = { it.text },
                set = { state, color -> state.copy(text = color) }
            ),
            ColorBinding(
                label = "Shared border",
                summary = "theme.xml: terminal_border_color",
                get = { it.border },
                set = { state, color -> state.copy(border = color) }
            ),
            ColorBinding(
                label = "Panel background",
                summary = "theme.xml: terminal_window_background_color",
                get = { it.panelBg },
                set = { state, color -> state.copy(panelBg = color) }
            ),
            ColorBinding(
                label = "Header background",
                summary = "theme.xml: terminal_header_background_color",
                get = { it.headerBg },
                set = { state, color -> state.copy(headerBg = color) }
            ),
            ColorBinding(
                label = "Header border",
                summary = "theme.xml: terminal_header_border_color",
                get = { it.headerTabBorder },
                set = { state, color -> state.copy(headerTabBorder = color) }
            ),
            ColorBinding(
                label = "Header text",
                summary = "theme.xml: module_text_color / module_header_text_color",
                get = { it.headerText },
                set = { state, color -> state.copy(headerText = color) }
            ),
            ColorBinding(
                label = "Key background",
                summary = "theme.xml: module_button_background_color",
                get = { it.inputBg },
                set = { state, color -> state.copy(inputBg = color) }
            ),
            ColorBinding(
                label = "Key text",
                summary = "theme.xml: module_button_text_color",
                get = { it.inputText },
                set = { state, color -> state.copy(inputText = color) }
            ),
            ColorBinding(
                label = "Output background",
                summary = "theme.xml: output_background_color",
                get = { it.outputBg },
                set = { state, color -> state.copy(outputBg = color) }
            ),
            ColorBinding(
                label = "Output border",
                summary = "theme.xml: output_border_color",
                get = { it.outputBorder },
                set = { state, color -> state.copy(outputBorder = color) }
            )
        )
    }

    private fun colorChannel(color: Int, channel: Char): Int {
        return when (channel) {
            'A' -> Color.alpha(color)
            'R' -> Color.red(color)
            'G' -> Color.green(color)
            else -> Color.blue(color)
        }
    }

    private fun replaceColorChannel(color: Int, channel: Char, value: Int): Int {
        val clamped = value.coerceIn(0, 255)
        return Color.argb(
            if (channel == 'A') clamped else Color.alpha(color),
            if (channel == 'R') clamped else Color.red(color),
            if (channel == 'G') clamped else Color.green(color),
            if (channel == 'B') clamped else Color.blue(color)
        )
    }

    private fun colorHex(color: Int): String {
        return String.format(Locale.US, "#%08X", color)
    }

    private data class ColorBinding(
        val label: String,
        val summary: String,
        val get: (SettingsTheme) -> Int,
        val set: (SettingsTheme, Int) -> SettingsTheme
    )

    private data class SettingsTheme(
        val bg: Int = Color.rgb(2, 6, 4),
        val text: Int = Color.rgb(187, 225, 136),
        val border: Int = Color.rgb(142, 184, 83),
        val panelBg: Int = Color.rgb(13, 23, 20),
        val headerBg: Int = Color.rgb(21, 34, 24),
        val headerTabBorder: Int = Color.rgb(142, 184, 83),
        val headerText: Int = Color.rgb(187, 225, 136),
        val inputBg: Int = Color.rgb(3, 12, 8),
        val inputText: Int = Color.rgb(113, 255, 171),
        val inputBorder: Int = Color.rgb(142, 184, 83),
        val outputBg: Int = 0,
        val outputBorder: Int = 0,
        val rowBg: Int = Color.rgb(9, 22, 15),
        val actionBg: Int = Color.rgb(7, 30, 17),
        val accent: Int = Color.rgb(113, 255, 171),
        val dim: Int = Color.rgb(117, 164, 91),
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
    ) {
        companion object {
            val DEFAULT = SettingsTheme()
        }
    }

    private class ColorSwatchDrawable(
        private val color: Int,
        private val borderColor: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            val cell = max(6, bounds.height() / 3)
            var y = bounds.top
            var row = 0
            while (y < bounds.bottom) {
                var x = bounds.left
                var column = 0
                while (x < bounds.right) {
                    paint.style = Paint.Style.FILL
                    paint.color = if ((row + column) % 2 == 0) Color.rgb(54, 62, 58) else Color.rgb(12, 16, 14)
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        min(x + cell, bounds.right).toFloat(),
                        min(y + cell, bounds.bottom).toFloat(),
                        paint
                    )
                    x += cell
                    column++
                }
                y += cell
                row++
            }

            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawRect(bounds, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = borderColor
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class KeyboardTerminalBorderDrawable(
        private val fillColor: Int,
        private val borderColor: Int,
        val strokeWidthPx: Int,
        private val radiusPx: Float,
        private val dashed: Boolean,
        private val dashLengthPx: Float,
        private val dashGapPx: Float,
        private val cyberdeck: Boolean = false,
        private val cyberdeckNotch: Boolean = true
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = borderColor
            strokeWidth = strokeWidthPx.toFloat()
            strokeJoin = Paint.Join.MITER
            if (dashed && strokeWidthPx > 0 && dashLengthPx > 0f && dashGapPx > 0f) {
                pathEffect = DashPathEffect(
                    floatArrayOf(max(1f, dashLengthPx), max(1f, dashGapPx)),
                    0f
                )
            }
        }
        private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, strokeWidthPx / 2f)
            color = withAlphaComponent(borderColor, 95)
        }
        private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val topCutouts = ArrayList<RectF>()
        private val bottomCutouts = ArrayList<RectF>()

        fun setCutouts(top: List<RectF>, bottom: List<RectF>) {
            topCutouts.clear()
            topCutouts.addAll(top)
            bottomCutouts.clear()
            bottomCutouts.addAll(bottom)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return

            if (cyberdeck) {
                drawCyberdeck(canvas)
                return
            }

            val fillRect = RectF(b)
            canvas.drawRoundRect(fillRect, radiusPx, radiusPx, fillPaint)
            if (strokeWidthPx <= 0) return

            val inset = strokeWidthPx / 2f
            val strokeRect = RectF(
                b.left + inset,
                b.top + inset,
                b.right - inset,
                b.bottom - inset
            )
            canvas.drawRoundRect(strokeRect, radiusPx, radiusPx, strokePaint)
            drawCutouts(canvas, max(strokeWidthPx * 3f, 6f))
        }

        private fun drawCyberdeck(canvas: Canvas) {
            val b = bounds
            val width = b.width().toFloat()
            val height = b.height().toFloat()
            if (width <= 0f || height <= 0f) return

            val left = b.left.toFloat()
            val top = b.top.toFloat()
            val right = b.right.toFloat()
            val bottom = b.bottom.toFloat()
            val maxCornerCut = max(20f, strokeWidthPx * 8f)
            val maxNotchDepth = max(12f, strokeWidthPx * 6f)
            val cornerCut = min(min(max(8f, height * 0.34f), width * 0.18f), maxCornerCut)
            val notchDepth = min(min(max(8f, height * 0.22f), width * 0.16f), maxNotchDepth)
            val notchHalfHeight = min(max(1.5f, height * 0.04f), 4f)
            val notchCenter = top + height * 0.52f

            val path = Path()
            path.moveTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right, bottom - cornerCut)
            path.lineTo(right - cornerCut, bottom)
            path.lineTo(left, bottom)
            if (cyberdeckNotch) {
                path.lineTo(left, notchCenter + notchHalfHeight)
                path.lineTo(left + notchDepth, notchCenter + notchHalfHeight)
                path.lineTo(left + notchDepth, notchCenter - notchHalfHeight)
                path.lineTo(left, notchCenter - notchHalfHeight)
            }
            path.close()

            canvas.drawPath(path, fillPaint)
            if (strokeWidthPx > 0) {
                canvas.drawPath(path, strokePaint)
                drawCyberdeckDetails(canvas, left, top, right, bottom, width, height, cornerCut, if (cyberdeckNotch) notchDepth else 0f)
            }
            drawCutouts(canvas, max(strokeWidthPx * 4f, 10f))
        }

        private fun drawCyberdeckDetails(
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
            if (width < 56f || height < 44f) return
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

        private fun drawCutouts(canvas: Canvas, cutoutHeight: Float) {
            val b = bounds
            if (topCutouts.isEmpty() && bottomCutouts.isEmpty()) return

            for (cutout in topCutouts) {
                canvas.drawRect(
                    b.left + cutout.left,
                    b.top.toFloat(),
                    b.left + cutout.right,
                    b.top + cutoutHeight,
                    cutoutPaint
                )
            }
            for (cutout in bottomCutouts) {
                canvas.drawRect(
                    b.left + cutout.left,
                    b.bottom - cutoutHeight,
                    b.left + cutout.right,
                    b.bottom.toFloat(),
                    cutoutPaint
                )
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            detailPaint.alpha = alpha
            cutoutPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            detailPaint.colorFilter = colorFilter
            cutoutPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            private fun withAlphaComponent(color: Int, alpha: Int): Int {
                return Color.argb(
                    alpha.coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            }
        }
    }

    private class TerminalCrtOverlayDrawable(
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
            scanlinePaint.color = Color.argb(38, 0, 0, 0)

            beamPaint.style = Paint.Style.FILL
            beamPaint.color = Color.argb(8, 255, 255, 255)

            maskPaint.style = Paint.Style.STROKE
            maskPaint.strokeWidth = 1f
            maskPaint.color = Color.argb(16, 0, 0, 0)

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
                intArrayOf(Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
                floatArrayOf(0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return

            canvas.drawRect(b, tintPaint)

            var y = b.top.toFloat()
            while (y < b.bottom) {
                canvas.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + scanlineHeightPx, scanlinePaint)
                canvas.drawRect(
                    b.left.toFloat(),
                    y + scanlineHeightPx,
                    b.right.toFloat(),
                    y + scanlineHeightPx + beamHeightPx,
                    beamPaint
                )
                y += scanlineStepPx
            }

            var x = b.left.toFloat()
            while (x < b.right) {
                canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), maskPaint)
                x += maskStepPx
            }

            canvas.drawRect(b, vignettePaint)
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
        private const val REQUEST_BACKGROUND = 41
        private const val REQUEST_PROFILE_BACKUP = 42
        private const val REQUEST_PROFILE_RESTORE = 43
        private const val BAR_SEGMENTS = 16
        private const val THEME_OVERRIDE_PREFIX = "theme."

        private val SURFACE_BG = Color.rgb(2, 6, 4)
        private val PANEL_BG = Color.rgb(13, 23, 20)
        private val PANEL_DEEP_BG = Color.rgb(7, 16, 13)
        private val ROW_BG = Color.rgb(9, 22, 15)
        private val TAB_BG = Color.rgb(21, 34, 24)
        private val ACTION_BG = Color.rgb(7, 30, 17)
        private val INPUT_BG = Color.rgb(3, 12, 8)
        private val BORDER = Color.rgb(142, 184, 83)
        private val TERMINAL_TEXT = Color.rgb(187, 225, 136)
        private val TERMINAL_ACCENT = Color.rgb(113, 255, 171)
        private val TERMINAL_DIM = Color.rgb(117, 164, 91)
    }
}
