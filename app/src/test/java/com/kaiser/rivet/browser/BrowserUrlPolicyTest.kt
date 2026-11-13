package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test fun acceptsUppercaseHttpAndHttpsSchemes() {
        assertEquals("HTTP://example.com/path", BrowserUrlPolicy.validate("HTTP://example.com/path"))
        assertEquals("HTTPS://example.com/docs", BrowserUrlPolicy.validate("HTTPS://example.com/docs"))
    }
}
