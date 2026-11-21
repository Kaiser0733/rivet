package com.kaiser.rivet.browser

import com.kaiser.rivet.ui.browser.BrowserTabs
import com.kaiser.rivet.ui.browser.BrowserWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun chromeShowsDomainsAndFriendlyBlankTabs() {
        assertEquals("docs.example.com", BrowserTabs.addressLabel("https://docs.example.com/api?q=x"))
        assertEquals("Web address", BrowserTabs.addressLabel("about:blank"))
        assertEquals("New tab", BrowserTabs.tabTitle("about:blank", "about:blank"))
        assertEquals("Docs", BrowserTabs.tabTitle("Docs", "https://docs.example.com/api"))
        assertEquals("docs.example.com", BrowserTabs.tabTitle("", "https://docs.example.com/api"))
    }

    @Test fun blankUrlFallsBackToANeutralTitle() {
        assertEquals("New tab", BrowserTabs.title("", ""))
    }
    @Test fun closingTheActiveWindowSelectsTheLastRemainingWindowAndItsUrl() {
        val target = BrowserTabs.closeTarget(windows, activeId = "b", closingId = "b")
        assertEquals("a", target.activeId)
        assertEquals("https://example.com/", target.url)
        assertEquals("Home", target.title)
        assertFalse(target.readerMode)
        assertEquals("", target.readerText)
        assertFalse(target.pageReady)
    }

    @Test fun closingAnInactiveWindowKeepsTheActivePage() {
        val target = BrowserTabs.closeTarget(windows, activeId = "b", closingId = "a")
        assertEquals("b", target.activeId)
        assertEquals("https://docs.example.com/api", target.url)
        assertEquals("", target.title)
    }

}
