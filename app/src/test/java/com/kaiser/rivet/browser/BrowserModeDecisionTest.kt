package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserModeDecisionTest {
    // JiTTest: the 1.4.0 parent routed user-open to the reader face and a read
    // popped the visible sheet. These assertions pin the opposite behaviour.
    @Test fun userOpenIsAlwaysTheVisualSurfaceAndFullScreen() {
        assertEquals(BrowserSurface.Visual, BrowserModeDecision.surfaceFor(BrowserIntent.UserOpen))
        assertTrue(BrowserModeDecision.showsVisualScreen(BrowserIntent.UserOpen))
        assertTrue(BrowserModeDecision.startsRenderer(BrowserIntent.UserOpen))
    }

    @Test fun toolReadFindAndOpenStayInvisibleText() {
        listOf(BrowserIntent.ToolOpen, BrowserIntent.ToolRead, BrowserIntent.ToolFind).forEach { intent ->
            assertEquals(BrowserSurface.Text, BrowserModeDecision.surfaceFor(intent))
            assertFalse(BrowserModeDecision.showsVisualScreen(intent))
            assertFalse(BrowserModeDecision.startsRenderer(intent))
        }
    }

    @Test fun everyInteractionRoutesToVisualWithoutHijackingTheScreen() {
        listOf(BrowserIntent.ToolClick, BrowserIntent.ToolType, BrowserIntent.ToolScroll,
            BrowserIntent.ToolBack, BrowserIntent.ToolShot).forEach { intent ->
            assertEquals(BrowserSurface.Visual, BrowserModeDecision.surfaceFor(intent))
            assertTrue(BrowserModeDecision.startsRenderer(intent))
            assertFalse(BrowserModeDecision.showsVisualScreen(intent))
        }
    }

    @Test fun aTextPathThatNeedsTheDomEscalatesToVisualOnly() {
        assertEquals(BrowserSurface.Visual, BrowserModeDecision.surfaceFor(BrowserIntent.TextNeedsRenderer))
        assertTrue(BrowserModeDecision.startsRenderer(BrowserIntent.TextNeedsRenderer))
        assertFalse(BrowserModeDecision.showsVisualScreen(BrowserIntent.TextNeedsRenderer))
    }
}
