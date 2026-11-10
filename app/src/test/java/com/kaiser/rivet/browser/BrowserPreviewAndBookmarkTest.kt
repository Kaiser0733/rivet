package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPreviewAndBookmarkTest {
    @Test fun loopbackPreviewIsHttpAndFileIsNeverOpened() {
        assertTrue(BrowserUrlPolicy.validate("http://127.0.0.1:8123/index.html").startsWith("http://127.0.0.1"))
        val failure = runCatching { BrowserUrlPolicy.validate("file:///sdcard/secret.html") }.exceptionOrNull()
        assertTrue(failure is BrowserFailure)
        assertEquals("browser_scheme", (failure as BrowserFailure).code)
    }

    @Test fun bookmarkPathsCannotEscapeTheProjectOrHitGoalFiles() {
        val path = BrowserBookmarkPath.file("../../Rivet: docs", 123)
        assertTrue(path.startsWith(".rivet/browser-links/"))
        assertFalse(path.contains(".."))
        assertFalse(path.endsWith("goal.md"))
        assertEquals("[InternetShortcut]\nURL=https://example.com\nTitle=Docs\n",
            BrowserBookmarkPath.contents("Docs", "https://example.com"))
    }
}
