package com.dvil.retui.keyboard

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class KeyboardProbeActivity : Activity() {
    private lateinit var input: ProbeEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(18), dp(18), dp(18))
        root.setBackgroundColor(Color.rgb(2, 6, 4))

        val title = TextView(this)
        title.text = "RETUI KEYBOARD PROBE"
        title.typeface = Typeface.MONOSPACE
        title.setTextColor(Color.rgb(194, 255, 210))
        title.textSize = 16f
        title.gravity = Gravity.CENTER
        root.addView(title, LinearLayout.LayoutParams(-1, dp(38)))

        input = ProbeEditText(this)
        input.isFocusableInTouchMode = true
        input.hint = "$ "
        input.typeface = Typeface.MONOSPACE
        input.setTextColor(Color.rgb(154, 255, 181))
        input.setHintTextColor(Color.rgb(70, 150, 95))
        input.setBackgroundColor(Color.rgb(8, 18, 12))
        input.setPadding(dp(10), dp(10), dp(10), dp(10))
        input.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN
        input.minLines = 6
        input.gravity = Gravity.TOP or Gravity.START
        root.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        input.postDelayed({
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
        }, 250)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private class ProbeEditText(context: Context) : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs)
            outAttrs.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN
            outAttrs.extras = (outAttrs.extras ?: Bundle()).apply {
                putString("keyboard_context", "/storage/emulated/0")
                putString("keyboard_mode", "command")
                putInt("theme_bg", Color.rgb(2, 6, 4))
                putInt("theme_text", Color.rgb(102, 255, 147))
                putInt("theme_border", Color.rgb(48, 180, 94))
                putInt("terminal_bg", Color.rgb(8, 18, 12))
                putInt("module_header_bg_color", Color.rgb(10, 38, 22))
                putInt("module_header_text_color", Color.rgb(194, 255, 210))
                putInt("input_bg_color", Color.rgb(13, 29, 19))
                putInt("input_text_color", Color.rgb(154, 255, 181))
                putInt("input_font_size", 14)
                putBoolean("enable_cyberdeck_mode", true)
                putBoolean("enable_crt_filter", true)
            }
            outAttrs.privateImeOptions =
                "com.dvil.retui.keyboard:keyboard_context=/storage/emulated/0;keyboard_mode=command;theme_bg=#020604;terminal_bg=#08120c;theme_border=#30b45e;input_bg_color=#0d1d13;input_text_color=#9affb5;enable_cyberdeck_mode=true;enable_crt_filter=true"
            return connection
        }
    }
}
