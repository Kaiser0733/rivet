package com.kaiser.rivet.browser

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserOpenPreflightTest {
    @Test fun typedLoopbackAddressesGetAnHttpScheme() {
        assertEquals("http://127.0.0.1:45179/",
            BrowserOpenPreflight.normalize("127.0.0.1:45179/"))
        assertEquals("http://localhost:3000",
            BrowserOpenPreflight.normalize("localhost:3000"))
        assertEquals("http://[::1]:8080/x",
            BrowserOpenPreflight.normalize("[::1]:8080/x"))
        assertEquals("http://127.0.0.1:45179/",
            BrowserOpenPreflight.normalize("  http://127.0.0.1:45179/  "))
    }

    @Test fun wildcardAddressIsRejectedWithTheWorkingFix() {
        val failure = runCatching { BrowserOpenPreflight.normalize("0.0.0.0:8080/") }
            .exceptionOrNull() as BrowserFailure
        assertEquals("browser_invalid_url", failure.code)
        assertTrue(failure.message.contains("127.0.0.1:PORT"))
        assertTrue(failure.message.contains("preview_root/preview_entry"))
    }

    @Test fun cleartextNonLoopbackIsRejectedBeforeWebView()  {
        val failure = runCatching { BrowserOpenPreflight.normalize("http://example.com/a") }
            .exceptionOrNull() as BrowserFailure
        assertEquals("browser_cleartext_blocked", failure.code)
        assertTrue(failure.message.contains("https://"))
        assertTrue(failure.message.contains("preview_root/preview_entry"))
        assertTrue(failure.message.contains("127.0.0.1"))
        assertEquals("https://example.com/a",
            BrowserOpenPreflight.normalize("https://example.com/a"))
    }

    @Test fun otherSchemesStillReachTheSharedUrlPolicy() {
        val failure = runCatching { BrowserOpenPreflight.normalize("file:///tmp/x.html") }
            .exceptionOrNull() as BrowserFailure
        assertEquals("browser_scheme", failure.code)
        assertTrue(failure.message.isNotBlank())
    }

    @Test fun aDeadLoopbackPortNamesPreviewAndSpawnFixes() {
        val port = ServerSocket(0).use { it.localPort }
        val failure = runCatching {
            BrowserOpenPreflight.probeLoopback("http://127.0.0.1:$port/",
                connectTimeoutMs = 250, readTimeoutMs = 500)
        }.exceptionOrNull() as BrowserFailure
        assertEquals("browser_loopback_unreachable", failure.code)
        assertTrue(failure.message.contains("preview_root/preview_entry"))
        assertTrue(failure.message.contains("spawn_process"))
    }

    @Test fun aLiveLoopbackServerReturnsItsStatus() {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        val server = ServerSocket(0)
        val worker = thread(start = true, isDaemon = true) {
            runCatching {
                server.accept().use { socket: Socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                }
            }
        }
        try {
            val probe = BrowserOpenPreflight.probeLoopback(
                "http://127.0.0.1:${server.localPort}/missing",
                connectTimeoutMs = 1_000, readTimeoutMs = 2_000)
            assertEquals(404, probe.httpStatus)
            assertNotNull(probe.warning)
            assertTrue(probe.warning!!.contains("404"))
            assertTrue(probe.warning!!.contains("preview_entry"))
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
