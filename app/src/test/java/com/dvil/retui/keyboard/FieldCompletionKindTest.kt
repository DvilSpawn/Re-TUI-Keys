package com.dvil.retui.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldCompletionKindTest {
    @Test
    fun genericWebTextKeepsWordSuggestions() {
        assertNull(fieldCompletionKindForInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        ))
        assertEquals(
            FieldCompletionKind.URL,
            fieldCompletionKindForInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
        )
        assertEquals(
            FieldCompletionKind.EMAIL,
            fieldCompletionKindForInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
    }
}
