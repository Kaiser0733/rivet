package com.kaiser.rivet.runtime

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android 11+ hides installed packages unless <queries> declares them, so
 * TermuxBridge.installed() would report "Not installed" on a phone that has Termux.
 */
class TermuxVisibilityTest {
    private val main = listOf(File("src/main"), File("app/src/main"))
        .first { it.isDirectory }
    private val manifest = File(main, "AndroidManifest.xml")

    @Test fun queriesDeclareTheTermuxPackageForVisibility() {
        assertTrue(manifest.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(manifest).documentElement
        val queries = root.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val packages = (queries.item(0) as Element).getElementsByTagName("package")
        val names = (0 until packages.length)
            .map { (packages.item(it) as Element).getAttribute("android:name") }
        assertEquals(listOf("com.termux"), names)
    }

    @Test fun theTermuxResultReceiptIsNotDeclaredAsAReceiver() {
        assertTrue(manifest.isFile)
        val text = manifest.readText()
        // The process boundary allows only the AndroidX profile receiver; the receipt is
        // registered while a command waits, so it must never appear in the manifest.
        assertFalse("no receiver may be declared in the app manifest", text.contains("<receiver"))
        assertFalse("the Termux receipt must stay runtime-registered", text.contains("TermuxResultReceiver"))
    }
}
