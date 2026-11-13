package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserJsParsersTest {

    @Test
    fun clickTargetRejectsNullOrMissingFoundFlag() {
        assertFalse(BrowserJsParsers.parseClickTarget("null").found)
        assertFalse(BrowserJsParsers.parseClickTarget("{}").found)
        assertFalse(BrowserJsParsers.parseClickTarget("""{"found":"yes"}""").found)

        val valid = BrowserJsParsers.parseClickTarget(
            """{"found":true,"risky":false,"description":"Next page","inForm":true,"tag":"button"}"""
        )
        assertTrue(valid.found)
        assertEquals("Next page", valid.description)
        assertTrue(valid.inForm)
        assertEquals("button", valid.tag)
    }

    @Test
    fun fractionalScrollYValuesParseInsteadOfReturningNullOrZero() {
        assertEquals(124, BrowserJsParsers.parseScrollYValue("123.75"))
        assertEquals(42, BrowserJsParsers.parseScrollYValue("\"42.2\""))
        assertEquals(250, BrowserJsParsers.parseSnapshotScrollY("""{"scrollY":249.6,"fields":[]}"""))
    }
}
