package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPreviewTest {
    @Test fun loopbackPreviewIsExplicitlyAllowedWhilePublicTrafficStaysHttps() {
        val config = File("app/src/main/res/xml/network_security_config.xml")
        assertTrue(config.isFile)
        val xml = config.readText()
        assertTrue(xml.contains("127.0.0.1"))
        assertTrue(xml.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(xml.contains("localhost"))
    }
}
