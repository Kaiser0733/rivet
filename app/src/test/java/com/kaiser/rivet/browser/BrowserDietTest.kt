package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDietTest {
    @Test fun normalDietBlocksTrackersAndMediaButNotOrdinaryImages() {
        val rules = BrowserDiet.rulesFor(BrowserTier.Normal, fullPage = false)
        assertTrue(BrowserDiet.shouldBlock("https://www.google-analytics.com/collect", BrowserResourceKind.Tracker, 1, rules))
        assertFalse(BrowserDiet.shouldBlock("https://example.com/photo.jpg", BrowserResourceKind.Image, 200_000, rules))
        assertTrue(BrowserDiet.shouldBlock("https://example.com/movie.mp4", BrowserResourceKind.Media, 1, rules))
    }

    @Test fun strictDietBlocksFontsAndLargeImages() {
        val rules = BrowserDiet.rulesFor(BrowserTier.StrictDiet, fullPage = false)
        assertTrue(BrowserDiet.shouldBlock("https://example.com/font.woff2", BrowserResourceKind.Font, 1, rules))
        assertTrue(BrowserDiet.shouldBlock("https://example.com/hero.jpg", BrowserResourceKind.Image, 128_000, rules))
        assertFalse(BrowserDiet.shouldBlock("https://example.com/icon.png", BrowserResourceKind.Image, 3_000, rules))
    }

    @Test fun readerFirstBlocksImagesAndFonts() {
        val rules = BrowserDiet.rulesFor(BrowserTier.ReaderFirst, fullPage = false)
        assertTrue(BrowserDiet.shouldBlock("https://example.com/icon.png", BrowserResourceKind.Image, 3_000, rules))
        assertTrue(rules.blockMedia)
    }

    @Test fun fullPageRestoresTheRichVersion() {
        val rules = BrowserDiet.rulesFor(BrowserTier.StrictDiet, fullPage = true)
        assertFalse(BrowserDiet.shouldBlock("https://www.google-analytics.com/collect", BrowserResourceKind.Tracker, 1, rules))
        assertFalse(BrowserDiet.shouldBlock("https://example.com/font.woff2", BrowserResourceKind.Font, 1, rules))
    }
}
