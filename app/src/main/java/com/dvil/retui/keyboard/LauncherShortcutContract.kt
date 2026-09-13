package com.dvil.retui.keyboard

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URI

internal object LauncherShortcutContract {
    const val BUNDLE_KEY = "keyboard_shortcuts_json"
    const val ACTION_RUN = "com.dvil.tui_renewed.action.RUN_KEYBOARD_SHORTCUT"
    const val EXTRA_ID = "keyboard_shortcut_id"
    const val EXTRA_TOKEN = "keyboard_shortcut_token"
    const val LAUNCHER_PACKAGE = "com.dvil.tui_renewed"
    const val LAUNCHER_ACTIVITY = "ohi.andre.consolelauncher.LauncherActivity"
    const val MAX_ACTIONS_PER_KEY = 2

    private const val PREF_KEY = "launcher.keyboardShortcuts"
    private const val VERSION = 1
    private const val MAX_PAYLOAD_LENGTH = 64 * 1024
    private const val MAX_TOKEN_LENGTH = 256
    private const val MAX_ID_LENGTH = 128
    private const val MAX_LABEL_LENGTH = 64
    private const val MAX_ICON_URI_LENGTH = 1024
    private const val ICON_AUTHORITY = "$LAUNCHER_PACKAGE.FILE_PROVIDER"
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{16,$MAX_TOKEN_LENGTH}")
    private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,$MAX_ID_LENGTH}")

    data class Shortcut(val id: String, val label: String, val iconUri: String)

    data class State(val token: String, val keys: Map<Char, List<Shortcut>>) {
        fun forKey(text: String?): List<Shortcut> {
            val key = text?.singleOrNull()?.lowercaseChar() ?: return emptyList()
            return keys[key].orEmpty()
        }
    }

    fun read(prefs: SharedPreferences): State? = parse(prefs.getString(PREF_KEY, null))

    fun accept(prefs: SharedPreferences, raw: String): State? {
        val parsed = parse(raw) ?: return null
        prefs.edit().putString(PREF_KEY, raw).apply()
        return parsed
    }

    fun parse(raw: String?): State? {
        if (raw.isNullOrBlank() || raw.length > MAX_PAYLOAD_LENGTH) return null
        return runCatching {
            val root = JSONObject(raw)
            require(root.getInt("version") == VERSION)
            val token = root.getString("token").validated(16, MAX_TOKEN_LENGTH).also {
                require(TOKEN_PATTERN.matches(it))
            }
            val keysObject = root.getJSONObject("keys")
            val keys = linkedMapOf<Char, List<Shortcut>>()
            val names = keysObject.keys()
            while (names.hasNext()) {
                val name = names.next()
                require(name.length == 1 && name[0] in 'a'..'z')
                val items = keysObject.getJSONArray(name)
                require(items.length() in 0..MAX_ACTIONS_PER_KEY)
                val shortcuts = buildList(items.length()) {
                    repeat(items.length()) { index ->
                        val item = items.getJSONObject(index)
                        val iconUri = item.getString("icon_uri").validated(1, MAX_ICON_URI_LENGTH)
                        val uri = URI(iconUri)
                        require(uri.scheme == "content" && uri.authority == ICON_AUTHORITY)
                        add(
                            Shortcut(
                                id = item.getString("id").validated(1, MAX_ID_LENGTH).also {
                                    require(ID_PATTERN.matches(it))
                                },
                                label = item.getString("label").validated(1, MAX_LABEL_LENGTH),
                                iconUri = iconUri
                            )
                        )
                    }
                }
                require(shortcuts.map { it.id }.distinct().size == shortcuts.size)
                if (shortcuts.isNotEmpty()) keys[name[0]] = shortcuts
            }
            State(token, keys)
        }.getOrNull()
    }

    private fun String.validated(min: Int, max: Int): String = trim().also {
        require(it.length in min..max && !it.any(Char::isISOControl))
    }
}
