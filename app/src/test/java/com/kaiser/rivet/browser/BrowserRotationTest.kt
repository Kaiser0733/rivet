package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRotationTest {
    @Test fun theMainActivityHandlesOrientationWithoutRecreatingTheWebView() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        val activity = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        assertTrue(activity.contains("orientation"))
        assertTrue(activity.contains("screenSize"))
    }
}
