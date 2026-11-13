package com.kaiser.rivet.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptLoopbackTest {
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
}
