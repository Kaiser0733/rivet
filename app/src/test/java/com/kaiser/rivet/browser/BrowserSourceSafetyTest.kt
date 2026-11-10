package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSourceSafetyTest {
    private fun source(name: String): String =
        listOf(File("src/main/java/com/kaiser/rivet/browser/$name"),
            File("app/src/main/java/com/kaiser/rivet/browser/$name"))
            .first { it.isFile }.readText()

    @Test fun theBrowserPackageNeverSpoofsAUserAgent() {
        val files = listOf(File("src/main/java/com/kaiser/rivet/browser"),
            File("app/src/main/java/com/kaiser/rivet/browser")).first { it.isDirectory }
            .walkTopDown().filter { it.extension == "kt" }
        assertTrue(files.toList().isNotEmpty())
        assertFalse(files.any { it.readText().contains("setUserAgentString") })
    }

    @Test fun thereIsNoRawEvalToolAndNoFileOpen() {
        val tools = source("BrowserTools.kt")
        assertFalse(tools.contains("\"browser_eval\""))
        val policy = source("ReaderService.kt")
        assertTrue(policy.contains("Project previews use the local preview server"))
        assertFalse(policy.contains("scheme in setOf(\"http\", \"https\", \"file\")"))
    }

    @Test fun passwordFieldsAreOnTheRefusalPath() {
        val safety = source("BrowserFormSafety.kt")
        assertTrue(safety.contains("current-password"))
        assertTrue(safety.contains("cc-number"))
        assertTrue(safety.contains("one-time-code"))
    }
}
