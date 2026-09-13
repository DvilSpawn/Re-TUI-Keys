package com.dvil.retui.keyboard

import java.util.Locale

/**
 * Carries the casing of what the user already typed over to the completion they accept.
 * Shift releases after the first letter, so by the time a suggestion is tapped the keyboard
 * state no longer knows that `Lab` started with a capital; the typed text itself does.
 */
internal object TypedWordCasing {
    fun apply(typed: String, suggestion: String, locale: Locale): String {
        val letters = typed.filter(Char::isLetter)
        if (letters.isEmpty() || suggestion.isEmpty()) return suggestion
        if (letters.length > 1 && letters.none(Char::isLowerCase) && letters.any(Char::isUpperCase)) {
            return suggestion.uppercase(locale)
        }
        val typedLower = typed.lowercase(locale)
        if (suggestion.startsWith(typedLower)) {
            return typed + suggestion.substring(typedLower.length)
        }
        if (!letters.first().isUpperCase()) return suggestion
        return suggestion.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(locale) else char.toString()
        }
    }
}
