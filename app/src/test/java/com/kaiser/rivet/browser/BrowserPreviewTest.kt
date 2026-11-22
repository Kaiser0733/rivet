package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPreviewTest {
    @Test fun aProjectPreviewIsOpenedOnTheVisualSurface() {
        fun source(name: String) = listOf(File("src/main/java/com/kaiser/rivet/browser/$name.kt"),
            File("app/src/main/java/com/kaiser/rivet/browser/$name.kt")).first { it.isFile }.readText()
        val open = source("BrowserTools").substringAfter("private suspend fun prepareOpen")
            .substringBefore("private suspend fun prepareClick")
        assertTrue(open.contains("session.openForAgent(normalized, args.bool(\"new_window\"), preview, claimRenderer)"))
        val sessionOpen = source("BrowserSession").substringAfter("suspend fun openForAgent")
            .substringBefore("val result = openText")
        assertTrue(sessionOpen.contains("if (projectPreview)"))
        assertTrue(sessionOpen.contains("return openVisual("))
    }
}
