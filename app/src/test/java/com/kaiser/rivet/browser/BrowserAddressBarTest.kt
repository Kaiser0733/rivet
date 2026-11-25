package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAddressBarTest {
    @Test fun wordsAndPunctuationBecomeOneGoogleQuery() {
        assertEquals("https://www.google.com/search?q=weather", BrowserUrlPolicy.addressBarTarget("weather"))
        assertEquals("https://www.google.com/search?q=caf%C3%A9+%26+tea%3F+%23menu",
            BrowserUrlPolicy.addressBarTarget("  café & tea? #menu  "))
        assertEquals("https://www.google.com/search?q=example.org+opening+hours",
            BrowserUrlPolicy.addressBarTarget("example.org opening hours"))
    }

    @Test fun addressesKeepTheirDestinationAndUseExistingNavigationPolicy() {
        assertEquals("https://example.org/a?q=one%20two#section",
            BrowserUrlPolicy.addressBarTarget(" https://example.org/a?q=one%20two#section "))
        assertEquals("https://example.org/maps", BrowserUrlPolicy.addressBarTarget("example.org/maps"))
        assertEquals("http://localhost:8080/health",
            BrowserUrlPolicy.normalize(BrowserUrlPolicy.addressBarTarget("localhost:8080/health")))
        assertEquals("http://example.org/", BrowserUrlPolicy.addressBarTarget("http://example.org/"))
    }
}
