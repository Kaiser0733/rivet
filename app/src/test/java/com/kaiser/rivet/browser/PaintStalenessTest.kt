package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintStalenessTest {
    @Test fun domMovedWhileThePixelsStayedIdenticalIsStale() {
        assertTrue(PaintStaleness.isStale(FrameProbe(100, "same"), FrameProbe(101, "same")))
    }

    @Test fun changedPixelsAreFreshEvenWhenTheDomMoved() {
        assertFalse(PaintStaleness.isStale(FrameProbe(100, "old"), FrameProbe(101, "new")))
    }

    @Test fun aPageThatCannotScrollIsNotCalledStale() {
        assertFalse(PaintStaleness.isStale(FrameProbe(0, "same"), FrameProbe(0, "same")))
    }
}
