package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserKeyMappingTest {
    @Test fun namedKeysMapToAndroidAndW3cValues() {
        val arrows = mapOf(
            "ArrowUp" to (19 to "ArrowUp"),
            "ArrowDown" to (20 to "ArrowDown"),
            "ArrowLeft" to (21 to "ArrowLeft"),
            "ArrowRight" to (22 to "ArrowRight"),
        )
        for ((name, expected) in arrows) {
            val spec = BrowserKeyMapping.parse(name)
            assertEquals(name, spec.key)
            assertEquals(name, spec.code)
            assertEquals(expected.first, spec.keyCode)
        }
        assertEquals("Enter", BrowserKeyMapping.parse("Enter").code)
        assertEquals(66, BrowserKeyMapping.parse("Enter").keyCode)
        assertEquals(13, BrowserKeyMapping.parse("Enter").charCode)
        assertEquals(" ", BrowserKeyMapping.parse("Space").key)
        assertEquals(62, BrowserKeyMapping.parse("Space").keyCode)
        assertEquals(32, BrowserKeyMapping.parse(" ").charCode)
        assertEquals("Escape", BrowserKeyMapping.parse("Escape").code)
        assertEquals(111, BrowserKeyMapping.parse("Escape").keyCode)
        assertEquals("Tab", BrowserKeyMapping.parse("Tab").code)
        assertEquals(61, BrowserKeyMapping.parse("Tab").keyCode)
        assertEquals("Backspace", BrowserKeyMapping.parse("Backspace").code)
        assertEquals(67, BrowserKeyMapping.parse("Backspace").keyCode)
        assertEquals("Delete", BrowserKeyMapping.parse("Delete").code)
        assertEquals(112, BrowserKeyMapping.parse("Delete").keyCode)
        assertEquals("Home", BrowserKeyMapping.parse("Home").code)
        assertEquals(3, BrowserKeyMapping.parse("Home").keyCode)
        assertEquals(123, BrowserKeyMapping.parse("End").keyCode)
        assertEquals(92, BrowserKeyMapping.parse("PageUp").keyCode)
        assertEquals(93, BrowserKeyMapping.parse("PageDown").keyCode)
        assertEquals(131, BrowserKeyMapping.parse("F1").keyCode)
        assertEquals(142, BrowserKeyMapping.parse("F12").keyCode)
    }

    @Test fun asciiKeysMapToKeyCodesAndJavaScriptCodes() {
        assertEquals(29, BrowserKeyMapping.parse("a").keyCode)
        assertEquals("KeyA", BrowserKeyMapping.parse("a").code)
        assertEquals(54, BrowserKeyMapping.parse("z").keyCode)
        assertEquals(29, BrowserKeyMapping.parse("A").keyCode)
        assertEquals(7, BrowserKeyMapping.parse("0").keyCode)
        assertEquals("Digit0", BrowserKeyMapping.parse("0").code)
        assertEquals(16, BrowserKeyMapping.parse("9").keyCode)
        assertEquals(55, BrowserKeyMapping.parse(",").keyCode)
        assertEquals("Comma", BrowserKeyMapping.parse(",").code)
        assertEquals(74, BrowserKeyMapping.parse(";").keyCode)
        assertEquals("Semicolon", BrowserKeyMapping.parse(";").code)
        assertEquals(76, BrowserKeyMapping.parse("/").keyCode)
        assertEquals("Slash", BrowserKeyMapping.parse("/").code)
    }

    @Test fun combosAndExplicitModifiersMergeWithoutDuplicates() {
        val combo = BrowserKeyMapping.parse("Ctrl+Shift+A")
        assertEquals("A", combo.key)
        assertEquals(setOf(BrowserKeyModifier.Ctrl, BrowserKeyModifier.Shift), combo.modifiers)
        assertTrue(combo.ctrlKey)
        assertTrue(combo.shiftKey)
        assertFalse(combo.altKey)
        assertEquals(BrowserKeyMapping.META_CTRL_ON or BrowserKeyMapping.META_SHIFT_ON,
            combo.metaState)

        val explicit = BrowserKeyMapping.parse("ArrowLeft", listOf("alt", "meta", "shift"))
        assertEquals(setOf(BrowserKeyModifier.Alt, BrowserKeyModifier.Meta,
            BrowserKeyModifier.Shift), explicit.modifiers)
        assertEquals(BrowserKeyMapping.META_ALT_ON or BrowserKeyMapping.META_META_ON or
            BrowserKeyMapping.META_SHIFT_ON, explicit.metaState)

        val merged = BrowserKeyMapping.parse("Alt+Tab", listOf("alt"))
        assertEquals(setOf(BrowserKeyModifier.Alt), merged.modifiers)
    }

    @Test fun caseInsensitiveNamedKeysAndModifiersAreAccepted() {
        assertEquals(19, BrowserKeyMapping.parse("arrowup").keyCode)
        assertEquals(66, BrowserKeyMapping.parse("ENTER").keyCode)
        assertEquals(setOf(BrowserKeyModifier.Ctrl),
            BrowserKeyMapping.parse("control+s").modifiers)
    }

    @Test fun unknownNamesAreRejectedWithActionableExamples() {
        val failure = runCatching { BrowserKeyMapping.parse("Banana") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        val message = failure!!.message.orEmpty()
        assertTrue(message.contains("Unknown key"))
        assertTrue(message.contains("ArrowUp"))
        assertTrue(message.contains("Ctrl+s"))
        assertTrue(runCatching { BrowserKeyMapping.parse("a", listOf("hyper")) }
            .exceptionOrNull() is IllegalArgumentException)
    }
}
