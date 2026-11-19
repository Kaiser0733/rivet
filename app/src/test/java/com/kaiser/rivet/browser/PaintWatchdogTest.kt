package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintWatchdogTest {
    @Test fun aPaintedPageNeedsNoAction() {
        val watchdog = PaintWatchdog()
        assertEquals(PaintWatchdog.Directive.Arm, watchdog.onPageFinished("https://example.com/a"))
        assertEquals(PaintWatchdog.Directive.None,
            watchdog.onSample("https://example.com/a", PaintVerdict.Painted))
    }

    @Test fun theFirstBlankSampleRestartsAndTheSecondStops() {
        val watchdog = PaintWatchdog()
        watchdog.onPageFinished("https://example.com/a")
        assertEquals(PaintWatchdog.Directive.Restart,
            watchdog.onSample("https://example.com/a", PaintVerdict.BlankWithDom))
        watchdog.onPageFinished("https://example.com/a")
        assertEquals(PaintWatchdog.Directive.Stop,
            watchdog.onSample("https://example.com/a", PaintVerdict.BlankWithDom))
    }

    @Test fun aStaleFrameUsesTheSameOneRestartBudgetAsABlankFrame() {
        val watchdog = PaintWatchdog()
        watchdog.onPageFinished("https://example.com/a")
        assertEquals(PaintWatchdog.Directive.Restart,
            watchdog.onSample("https://example.com/a", PaintVerdict.Stale))
        watchdog.onPageFinished("https://example.com/a")
        assertEquals(PaintWatchdog.Directive.Stop,
            watchdog.onSample("https://example.com/a", PaintVerdict.Stale))
    }

    @Test fun aDifferentPageGetsItsOwnSingleRestart() {
        val watchdog = PaintWatchdog()
        watchdog.onPageFinished("https://example.com/a")
        watchdog.onSample("https://example.com/a", PaintVerdict.BlankNoDom)
        watchdog.onPageFinished("https://example.com/b")
        assertEquals(PaintWatchdog.Directive.Restart,
            watchdog.onSample("https://example.com/b", PaintVerdict.BlankNoDom))
    }

    @Test fun aHealthySampleClearsOnlyTheWatchdogErrorForTheSamePage() {
        val failed = BrowserUiState(url = "https://example.com/a", error = PAINT_WATCHDOG_ERROR)
        assertTrue(shouldClearPaintError(failed, "https://example.com/a", PaintVerdict.Painted))
        assertFalse(shouldClearPaintError(failed, "https://example.com/a", PaintVerdict.Stale))
        assertFalse(shouldClearPaintError(failed.copy(error = "Navigation failed"),
            "https://example.com/a", PaintVerdict.Painted))
        assertFalse(shouldClearPaintError(failed, "https://example.com/b", PaintVerdict.Painted))
    }

    @Test fun aBlankSampleForAPageThatWasNeverArmedIsIgnored() {
        assertEquals(PaintWatchdog.Directive.None,
            PaintWatchdog().onSample("https://example.com/a", PaintVerdict.BlankWithDom))
    }
}
