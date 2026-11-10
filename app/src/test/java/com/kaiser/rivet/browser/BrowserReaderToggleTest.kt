package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserReaderToggleTest {
    @Test fun leavingReaderRepaintsTheLiveWebView() {
        val source = listOf(File("src/main/java/com/kaiser/rivet/browser/BrowserSession.kt"),
            File("app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt"))
            .first { it.isFile }.readText()
        val hide = source.substringAfter("fun hideReader()").substringBefore("private suspend fun readRenderedDocument")
        assertTrue(hide.contains("invalidate()"))
        assertTrue(hide.contains("requestLayout()"))
    }
}
