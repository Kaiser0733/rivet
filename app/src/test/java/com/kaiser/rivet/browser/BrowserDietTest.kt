package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDietTest {
    @Test fun normalDietBlocksTrackersAndMediaButNotOrdinaryImages() {
        val rules = BrowserDiet.rulesFor(BrowserTier.Normal, fullPage = false)
        assertTrue(BrowserDiet.shouldBlock("https://www.google-analytics.com/collect", BrowserResourceKind.Tracker, 1, rules))
        assertFalse(BrowserDiet.shouldBlock("https://example.com/photo.jpg", BrowserResourceKind.Image, 200_000, rules))
        assertTrue(BrowserDiet.shouldBlock("https://example.com/movie.mp4", BrowserResourceKind.Media, 1, rules))
    }

    @Test fun strictDietTurnsImagesOffButKeepsFonts() {
        val rules = BrowserDiet.rulesFor(BrowserTier.StrictDiet, fullPage = false)
        assertFalse(BrowserDiet.shouldBlock("https://example.com/font.woff2", BrowserResourceKind.Font, 1, rules))
        assertTrue(BrowserDiet.shouldBlock("https://example.com/hero.jpg", BrowserResourceKind.Image, 128_000, rules))
        assertTrue(BrowserDiet.shouldBlock("https://example.com/icon.png", BrowserResourceKind.Image, 3_000, rules))
        assertTrue(BrowserDiet.placeholderPng().isNotEmpty())
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

    @Test fun mainFrameHtmlWithImageAcceptHeaderIsNeverClassifiedAsImageOrBlocked() {
        val accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        val kind = BrowserDiet.classifyResource(
            url = "https://en.m.wikipedia.org/wiki/Cat",
            isForMainFrame = true,
            acceptHeader = accept,
            secFetchDest = "document",
        )
        assertNotEquals(BrowserResourceKind.Image, kind)
        listOf(BrowserTier.Normal, BrowserTier.StrictDiet, BrowserTier.ReaderFirst).forEach { tier ->
            val rules = BrowserDiet.rulesFor(tier, fullPage = false)
            assertFalse(
                "Main-frame navigation must not be blocked under $tier",
                BrowserDiet.shouldBlock("https://en.m.wikipedia.org/wiki/Cat", kind, 0L, rules, isForMainFrame = true),
            )
        }
    }

    @Test fun subresourceImageBlockedWhileSubframeHtmlPassesThrough() {
        val strict = BrowserDiet.rulesFor(BrowserTier.StrictDiet, fullPage = false)
        val iframeKind = BrowserDiet.classifyResource(
            url = "https://example.com/embed",
            isForMainFrame = false,
            acceptHeader = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            secFetchDest = "iframe",
        )
        assertEquals(BrowserResourceKind.Other, iframeKind)
        assertFalse(BrowserDiet.shouldBlock("https://example.com/embed", iframeKind, 0L, strict))

        val imgKind = BrowserDiet.classifyResource(
            url = "https://example.com/render?id=42",
            isForMainFrame = false,
            acceptHeader = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            secFetchDest = "image",
        )
        assertEquals(BrowserResourceKind.Image, imgKind)
        assertTrue(BrowserDiet.shouldBlock("https://example.com/render?id=42", imgKind, 0L, strict))
    }
}
