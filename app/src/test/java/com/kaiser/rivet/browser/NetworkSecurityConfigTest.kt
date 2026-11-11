package com.kaiser.rivet.browser

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NetworkSecurityConfigTest {
    private val main = listOf(File("src/main"), File("app/src/main"))
        .first { it.isDirectory }
    private val config = File(main, "res/xml/network_security_config.xml")
    private val manifest = File(main, "AndroidManifest.xml")

    @Test fun theShippedConfigAllowsOnlyLoopbackCleartext() {
        assertTrue("network security config must ship", config.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(config).documentElement
        val base = root.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val domains = root.getElementsByTagName("domain-config")
        assertEquals(1, domains.length)
        val domainConfig = domains.item(0) as Element
        assertEquals("true", domainConfig.getAttribute("cleartextTrafficPermitted"))
        val permitted = (0 until domainConfig.getElementsByTagName("domain").length)
            .map { (domainConfig.getElementsByTagName("domain").item(it) as Element).textContent }
            .toSet()
        assertEquals(setOf("127.0.0.1", "localhost", "::1"), permitted)
    }

    @Test fun theManifestReferencesTheConfigWithoutWideningCleartext() {
        assertTrue(manifest.isFile)
        val text = manifest.readText()
        assertTrue(text.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(text.contains("android:usesCleartextTraffic=\"true\""))
        assertFalse("debug must not widen cleartext",
            File(main.parentFile, "debug/res/xml/network_security_config.xml").exists())
        val application = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(manifest).documentElement.getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config",
            application.getAttribute("android:networkSecurityConfig"))
        assertFalse(application.hasAttribute("android:usesCleartextTraffic"))
        assertNotNull(application)
    }
}
