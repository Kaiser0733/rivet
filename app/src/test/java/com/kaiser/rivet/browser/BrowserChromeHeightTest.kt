package com.kaiser.rivet.browser

import com.kaiser.rivet.ui.browser.BrowserChromeHeight
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserChromeHeightTest {
    @Test fun theHostPadsByTheMeasuredChromeHeight() {
        assertEquals(188, BrowserChromeHeight.hostTopPaddingPx(188))
    }

    @Test fun anEmptyChromeMeasuresZero() {
        assertEquals(0, BrowserChromeHeight.hostTopPaddingPx(0))
    }
}
