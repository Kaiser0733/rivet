package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFormSafetyTest {
    @Test fun passwordOtpAndCardAreNeverTypedOrCaptured() {
        for (field in listOf(
            BrowserFormField(name = "password", type = "password"),
            BrowserFormField(name = "code", autocomplete = "one-time-code"),
            BrowserFormField(name = "card", autocomplete = "cc-number"),
            BrowserFormField(name = "cvv"),
        )) {
            assertTrue(field.toString(), BrowserFormSafety.isSensitive(field))
            assertNotNull(BrowserFormSafety.refusal(field))
        }
    }

    @Test fun ordinaryTextFieldsAreSafe() {
        val field = BrowserFormField(name = "query", type = "search", placeholder = "Search docs")
        assertFalse(BrowserFormSafety.isSensitive(field))
        assertNull(BrowserFormSafety.refusal(field))
        assertEquals(listOf(field), BrowserFormSafety.capturable(listOf(field,
            BrowserFormField(name = "password", type = "password"))))
    }
}
