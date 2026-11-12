package com.kaiser.rivet.browser

import com.kaiser.rivet.ui.browser.UrlFieldSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UrlFieldSelectionTest {
    @Test fun focusingTheUrlFieldSelectsTheWholeUrl() {
        val url = "https://example.com/page?q=1"
        val selection = UrlFieldSelection.selectAll(url)
        assertEquals(0, selection.first)
        assertEquals(url.length, selection.last)
        assertEquals(url, url.substring(selection.first, selection.last))
    }

    @Test fun anEmptyFieldStaysCollapsedAtZero() {
        assertEquals(0..0, UrlFieldSelection.selectAll(""))
    }

    @Test fun theFieldSelectionCoversTheWholeUrlAndIsNotCollapsed() {
        val url = "https://example.com/page?q=1"
        val selection = UrlFieldSelection.selection(url)
        assertEquals(0, selection.start)
        assertEquals(url.length, selection.end)
        assertFalse(selection.collapsed)
        assertEquals(url, url.substring(selection.start, selection.end))
    }
}
