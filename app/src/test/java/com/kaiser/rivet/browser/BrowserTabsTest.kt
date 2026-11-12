package com.kaiser.rivet.browser

import com.kaiser.rivet.ui.browser.BrowserTabs
import com.kaiser.rivet.ui.browser.BrowserWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserTabsTest {
    private val windows = listOf(
        BrowserWindowInfo("a", "Home", "https://example.com/", active = false),
        BrowserWindowInfo("b", "", "https://docs.example.com/api", active = true),
    )

    @Test fun windowsMapToChipsInOrder() {
        val chips = BrowserTabs.chips(windows)
        assertEquals(listOf("a", "b"), chips.map { it.id })
        assertEquals("Home", chips[0].title)
        assertEquals("docs.example.com", chips[1].title)
        assertEquals(listOf(false, true), chips.map { it.active })
    }

    @Test fun onlyTheActiveWindowIsTheStripCloseTarget() {
        assertEquals("b", BrowserTabs.activeCloseTarget(windows))
        assertNull(BrowserTabs.activeCloseTarget(windows.map { it.copy(active = false) }))
    }

    @Test fun blankUrlFallsBackToANeutralTitle() {
        assertEquals("New tab", BrowserTabs.title("", ""))
    }
}
