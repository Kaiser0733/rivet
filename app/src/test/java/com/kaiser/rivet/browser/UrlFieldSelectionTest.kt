package com.kaiser.rivet.browser

import com.kaiser.rivet.ui.browser.UrlFieldSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlFieldSelectionTest {
    @Test fun initialTapFocusPreservesFullSelectionWhenOnValueChangeReportsTapCaret() {
        val url = "https://developer.android.com/404"
        val controller = UrlFieldSelection.Controller(url)

        val focused = controller.onFocusChanged(
            isFocused = true,
            currentText = url,
            currentSelection = UrlFieldSelection.Range(url.length, url.length),
        )
        assertEquals(UrlFieldSelection.Range(0, url.length), focused!!.selection)
        assertTrue(controller.pendingSelectAllOnTap)

        // Compose fires onValueChange with the collapsed tap offset right after onFocusChanged.
        val tapOverride = controller.onValueChange(url, UrlFieldSelection.Range(18, 18))
        assertTrue(tapOverride.keepFullSelection)
        assertEquals(UrlFieldSelection.Range(0, url.length), tapOverride.field.selection)
        assertFalse(tapOverride.field.selection.collapsed)
        assertFalse(controller.pendingSelectAllOnTap)

        // A second tap while already focused must place the caret normally.
        val secondTap = controller.onValueChange(url, UrlFieldSelection.Range(18, 18))
        assertFalse(secondTap.keepFullSelection)
        assertEquals(UrlFieldSelection.Range(18, 18), secondTap.field.selection)
        assertTrue(secondTap.field.selection.collapsed)
    }

    @Test fun typingImmediatelyAfterFocusReplacesInsteadOfBeingOverridden() {
        val url = "https://developer.android.com/404"
        val controller = UrlFieldSelection.Controller(url)

        controller.onFocusChanged(
            isFocused = true,
            currentText = url,
            currentSelection = UrlFieldSelection.Range(url.length, url.length),
        )
        val typed = controller.onValueChange("e", UrlFieldSelection.Range(1, 1))
        assertFalse(typed.keepFullSelection)
        assertEquals("e", typed.field.text)
        assertEquals(UrlFieldSelection.Range(1, 1), typed.field.selection)
        assertFalse(controller.pendingSelectAllOnTap)
    }

    @Test fun redundantFocusWhileFocusedAndBlurRefocusBehaveCleanly() {
        val url = "https://example.com/a?b=c"
        val controller = UrlFieldSelection.Controller(url)

        controller.onFocusChanged(true, url, UrlFieldSelection.Range(url.length, url.length))
        controller.onValueChange(url, UrlFieldSelection.Range(10, 10))
        controller.onValueChange(url, UrlFieldSelection.Range(10, 10))

        // Redundant focus callback while already focused must not clobber user caret.
        assertNull(controller.onFocusChanged(true, url, UrlFieldSelection.Range(10, 10)))

        // Settled keyboard focus without a tap callback clears the latch so later taps position caret.
        assertNull(controller.onFocusChanged(false, url, UrlFieldSelection.Range(10, 10)))
        val refocused = controller.onFocusChanged(true, url, UrlFieldSelection.Range(10, 10))
        assertEquals(UrlFieldSelection.Range(0, url.length), refocused!!.selection)
        controller.onFocusSettled()
        val tapAfterSettle = controller.onValueChange(url, UrlFieldSelection.Range(5, 5))
        assertFalse(tapAfterSettle.keepFullSelection)
        assertEquals(UrlFieldSelection.Range(5, 5), tapAfterSettle.field.selection)
    }

    @Test fun emptyUrlStaysCollapsedAtZeroOnFocus() {
        val controller = UrlFieldSelection.Controller("")
        val focused = controller.onFocusChanged(true, "", UrlFieldSelection.Range(0, 0))
        assertEquals("", focused!!.text)
        assertTrue(focused.selection.collapsed)
        assertEquals(0, focused.selection.start)
        assertEquals(0..0, UrlFieldSelection.selectAll(""))
    }
}
