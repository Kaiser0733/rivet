package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNoticeCopyTest {
    @Test fun degenerateViewportNeverReachesTheScreenAsADeveloperString() {
        val copy = browserNoticeCopy("browser_renderer_unavailable",
            "The browser viewport is degenerate (1x1 or not laid out). Call browser_reset(reopen=true) to rebuild the renderer.")
        assertFalse(copy.text.contains("browser_reset"))
        assertFalse(copy.text.contains("degenerate"))
        assertEquals("The page view didn't start. Try again.", copy.text)
        assertTrue(copy.retry)
    }

    @Test fun aRendererTakenByTheMemoryGovernorSaysSo() {
        val copy = browserNoticeCopy("browser_renderer_lost", "renderer released")
        assertTrue(copy.text.contains("save memory"))
        assertTrue(copy.retry)
    }

    @Test fun unknownDeveloperWordingIsReplacedByPlainWords() {
        val copy = browserNoticeCopy("something_new",
            "Call browser_reset(reopen=true) to rebuild the renderer.")
        assertFalse(copy.text.contains("browser_reset"))
        assertTrue(copy.retry)
    }

    @Test fun ordinaryHumanMessagesPassThroughUntouched() {
        val copy = browserNoticeCopy("browser_timeout", "The page took too long to load.")
        assertEquals("The page took too long to load.", copy.text)
        assertFalse(copy.retry)
    }

    @Test fun layoutWaitDistinguishesLostRendererFromSlowLayout() {
        assertEquals(LayoutWait.Waiting, layoutWait(width = 0, height = 0, rendererLost = false))
        assertEquals(LayoutWait.Ready, layoutWait(width = 2, height = 2, rendererLost = false))
        assertEquals(LayoutWait.Ready, layoutWait(width = 1080, height = 1920, rendererLost = false))
        assertEquals(LayoutWait.LostRenderer, layoutWait(width = 0, height = 0, rendererLost = true))
        // A renderer that was taken away is reported as lost even if it last measured.
        assertEquals(LayoutWait.LostRenderer, layoutWait(width = 1080, height = 1920, rendererLost = true))
    }
}
