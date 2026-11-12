package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollRestoreTest {
    @Test fun aShortPageDefersAndTheNextLayoutLandsTheSavedOffset() {
        val restore = ScrollRestore()
        restore.capture(500)
        assertNull(restore.attempt(contentHeight = 300))
        assertEquals(500, restore.attempt(contentHeight = 700))
        assertEquals(2, restore.attempts)
    }

    @Test fun aTallEnoughPageRestoresImmediately() {
        val restore = ScrollRestore()
        restore.capture(120)
        assertEquals(120, restore.attempt(contentHeight = 120))
    }

    @Test fun theTopNeedsNoScroll() {
        val restore = ScrollRestore()
        restore.capture(0)
        assertEquals(0, restore.attempt(contentHeight = 0))
    }
}
