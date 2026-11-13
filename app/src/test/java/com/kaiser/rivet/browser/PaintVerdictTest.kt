package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PaintVerdictTest {
    @Test fun paintedPixelsArePaintedEvenWhenTheDomIsPresent() {
        assertEquals(PaintVerdict.Painted, PaintVerdict.decide(1200, sampled = true, nearWhiteFraction = 0.10f))
    }

    @Test fun aDomScrolledFrameWithUnchangedPixelsIsStale() {
        assertEquals(PaintVerdict.Stale,
            PaintVerdict.decide(1200, sampled = true, nearWhiteFraction = 0.10f,
                domScrolled = true, frameChanged = false))
    }

    @Test fun aFreshFrameAfterAScrollNudgeIsPainted() {
        assertEquals(PaintVerdict.Painted,
            PaintVerdict.decide(1200, sampled = true, nearWhiteFraction = 0.10f,
                domScrolled = true, frameChanged = true))
    }

    @Test fun blankPixelsWithDomPresentAreThePaintWedge() {
        assertEquals(PaintVerdict.BlankWithDom, PaintVerdict.decide(1200, sampled = true, nearWhiteFraction = 0.995f))
    }

    @Test fun blankPixelsWithoutDomAreALoadProblemAndAreNotReloadedByTheReaderPath() {
        assertEquals(PaintVerdict.BlankNoDom, PaintVerdict.decide(0, sampled = true, nearWhiteFraction = 1f))
    }

    @Test fun anUnsampledFrameIsUnknownNeverBlank() {
        assertEquals(PaintVerdict.Unknown, PaintVerdict.decide(0, sampled = false, nearWhiteFraction = 1f))
    }

    @Test fun uniformDarkPixelsWithDomAreClassifiedAsBlankWithDom() {
        val pixels = IntArray(64) { 0xFF232324.toInt() }
        assertEquals(PaintVerdict.BlankWithDom, PaintVerdict.classifyPixels(800, pixels))
    }

    @Test fun uniformBlackPixelsWithoutDomAreClassifiedAsBlankNoDom() {
        val pixels = IntArray(64) { 0xFF000000.toInt() }
        assertEquals(PaintVerdict.BlankNoDom, PaintVerdict.classifyPixels(0, pixels))
    }

    @Test fun darkBackgroundWithLightTextRemainsPainted() {
        val pixels = IntArray(64) { index ->
            if (index % 16 == 0) 0xFFF0F0F0.toInt() else 0xFF18181A.toInt()
        }
        assertEquals(PaintVerdict.Painted, PaintVerdict.classifyPixels(800, pixels))
    }
}
