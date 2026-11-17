package com.kaiser.rivet.browser

import com.kaiser.rivet.ui.browser.UrlFieldSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlFieldSelectionTest {
    @Test fun selectAllCoversTheWholeUrl() {
        val url = "https://developer.android.com/404"
        assertEquals(0..url.length, UrlFieldSelection.selectAll(url))
        assertEquals(UrlFieldSelection.Range(0, url.length), UrlFieldSelection.focus(url).selection)
    }

    @Test fun emptyUrlSelectsAnEmptyRange() {
        val focused = UrlFieldSelection.focus("")
        assertEquals("", focused.text)
        assertTrue(focused.selection.collapsed)
        assertEquals(0..0, UrlFieldSelection.selectAll(""))
    }

    @Test fun collapsedRangeIsDistinguishedFromASelection() {
        assertTrue(UrlFieldSelection.Range(4, 4).collapsed)
        assertTrue(!UrlFieldSelection.Range(0, 4).collapsed)
    }
}
