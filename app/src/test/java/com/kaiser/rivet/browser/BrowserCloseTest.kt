package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCloseTest {
    @Test fun closeIsAnAgentToolOnTheSameSessionPath() {
        assertTrue(BrowserTools.NAMES.contains("browser_close"))
        val source = listOf(File("src/main/java/com/kaiser/rivet/browser/BrowserSession.kt"),
            File("app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt"))
            .first { it.isFile }.readText()
        val end = source.substringAfter("fun endSession()").substringBefore("override fun onTrimMemory")
        assertTrue(end.contains("snapshots.save"))
        assertTrue(end.contains("destroyRenderer()"))
    }
}
