package com.kaiser.rivet.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptLoopbackTest {
    private fun dispatchRequest(loopback: ChatGptLoopback, pathAndQuery: String): Int {
        val raw = "GET $pathAndQuery HTTP/1.1\r\nHost: 127.0.0.1:${loopback.actualPort}\r\nConnection: close\r\n\r\n"
        val out = ByteArrayOutputStream()
        loopback.processRequest(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)), out)
        val statusLine = out.toString(Charsets.US_ASCII.name()).lineSequence().firstOrNull()
            ?: error("Empty HTTP response for $pathAndQuery")
        return statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: error("Unexpected HTTP status line '$statusLine' for $pathAndQuery")
    }

    @Test fun awaitCallbackRespectsCoroutineCancellationImmediately() = runBlocking {
        val loopback = ChatGptLoopback()
        loopback.start()
        try {
            val waiter = async { loopback.awaitCallback(timeoutMs = 60_000L) }
            delay(50L)
            val startNs = System.nanoTime()
            waiter.cancel()
            val outcome = try {
                waiter.await()
                false
            } catch (_: CancellationException) {
                true
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue(outcome)
            assertTrue("Expected fast cancellation (<1000ms), took ${elapsedMs}ms", elapsedMs < 1_000L)
        } finally {
            loopback.stop()
        }
    }

    @Test fun prefixSpoofedPathAndMalformedEscapeDoNotCompleteCallback() = runBlocking {
        val loopback = ChatGptLoopback()
        try {
            val waiter = async { loopback.awaitCallback(timeoutMs = 5_000L) }
            delay(50L)
            // Spoofed path sharing callback prefix must return 404 and leave listener alive.
            assertEquals(404, dispatchRequest(loopback, "/auth/callbackEVIL?code=evil&state=s1"))
            // Malformed percent escape must be rejected cleanly with 400 and leave listener alive.
            assertEquals(400, dispatchRequest(loopback, "/auth/callback?code=%ZZ&state=s1"))
            // Genuine callback still succeeds.
            assertEquals(200, dispatchRequest(loopback, "/auth/callback?code=ok_code&state=s1&client_id=cid"))
            val cb = waiter.await() as ChatGptCallback.Code
            assertEquals("ok_code", cb.code)
        } finally {
            loopback.stop()
        }
    }

    @Test fun mismatchedOrErrorStateRequestsDoNotAbortActiveSignIn() = runBlocking {
        val loopback = ChatGptLoopback(expectedState = "expected_s1")
        try {
            val waiter = async { loopback.awaitCallback(timeoutMs = 5_000L) }
            delay(50L)
            assertEquals(400, dispatchRequest(loopback, "/auth/callback?code=evil&state=wrong_state"))
            assertEquals(400, dispatchRequest(loopback, "/auth/callback?error=access_denied&state=wrong_state"))
            assertEquals(400, dispatchRequest(loopback, "/auth/callback?code=missing_state"))
            assertEquals(200, dispatchRequest(loopback, "/auth/callback?code=good_code&state=expected_s1&client_id=cid"))
            val cb = waiter.await() as ChatGptCallback.Code
            assertEquals("good_code", cb.code)
            assertEquals("expected_s1", cb.state)
        } finally {
            loopback.stop()
        }
    }
}
