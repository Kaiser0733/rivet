package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPreviewTest {
    @Test fun aProjectPreviewIsOpenedOnTheVisualSurface() {
        val source = listOf(File("src/main/java/com/kaiser/rivet/browser/BrowserTools.kt"),
            File("app/src/main/java/com/kaiser/rivet/browser/BrowserTools.kt"))
            .first { it.isFile }.readText()
        val open = source.substringAfter("private suspend fun prepareOpen")
            .substringBefore("private suspend fun prepareClick")
        assertTrue(open.contains("session.openVisual"))
        assertTrue(open.contains("preview"))
    }
}
