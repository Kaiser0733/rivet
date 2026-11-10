package com.kaiser.rivet.browser

import androidx.compose.ui.unit.dp
import com.kaiser.rivet.ui.browser.BrowserChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserChromeTest {
    @Test fun pageSitsBelowTheTopBarAndAboveTheBottomBar() {
        assertEquals(BrowserChrome.TOP_BAR_DP.dp + 24.dp, BrowserChrome.topPadding(24.dp))
        assertEquals(BrowserChrome.BOTTOM_BAR_DP.dp + 16.dp, BrowserChrome.bottomPadding(16.dp))
        assertTrue(BrowserChrome.topPadding(0.dp).value > 0f)
        assertTrue(BrowserChrome.bottomPadding(0.dp).value > 0f)
    }
}
