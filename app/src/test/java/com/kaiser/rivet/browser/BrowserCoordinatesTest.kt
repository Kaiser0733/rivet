package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCoordinatesTest {
    @Test fun normalizedCoordinatesScaleToTheWebView() {
        assertEquals(0f, BrowserCoordinates.pixel(0, 720), 0.01f)
        assertEquals(360f, BrowserCoordinates.pixel(500, 720), 0.01f)
        assertEquals(1600f, BrowserCoordinates.pixel(1000, 1600), 0.01f)
        assertEquals(720f, BrowserCoordinates.pixel(1200, 720), 0.01f)
    }
}
