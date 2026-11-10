package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDesktopModeTest {
    @Test fun visualBrowserDefaultsToRealDesktop() {
        assertTrue(BrowserDesktopMode.DEFAULT_DESKTOP)
        assertTrue(BrowserDesktopMode.DESKTOP_USER_AGENT.contains("X11"))
        assertTrue(BrowserDesktopMode.DESKTOP_USER_AGENT.contains("Chrome/"))
    }

    @Test fun textReaderHasNoDesktopUserAgentPath() {
        val source = listOf(java.io.File("src/main/java/com/kaiser/rivet/browser/ReaderService.kt"),
            java.io.File("app/src/main/java/com/kaiser/rivet/browser/ReaderService.kt"))
            .first { it.isFile }.readText()
        assertTrue(source.isNotEmpty())
        assertEquals(false, source.contains("userAgent"))
    }
}
