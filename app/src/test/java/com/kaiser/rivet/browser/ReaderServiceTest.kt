package com.kaiser.rivet.browser

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReaderServiceTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    @Test fun fetchExtractsReaderTextWithoutARenderer() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "<html><head><title>API</title></head><body><main><h1>API</h1><p>Read this.</p></main></body></html>")
            .setHeader("Content-Type", "text/html; charset=utf-8"))
        val doc = ReaderService().fetch(server.url("/docs").toString())
        assertEquals("API", doc.title)
        assertTrue(doc.text.contains("Read this."))
        assertEquals(1, doc.chunks.size)
    }

    @Test fun redirectToAnAuthSurfaceStopsForHandoff() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "https://accounts.google.com/signin"))
        val failure = runCatching { ReaderService().fetch(server.url("/start").toString()) }.exceptionOrNull()
        assertTrue(failure is BrowserHandoff)
    }

    @Test fun anOversizedPageIsRefused() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat((ReaderService.MAX_READ_BYTES + 10).toInt()))
            .setHeader("Content-Type", "text/html"))
        val failure = runCatching { ReaderService().fetch(server.url("/huge").toString()) }.exceptionOrNull()
        assertTrue(failure is BrowserFailure)
        assertEquals("browser_too_large", (failure as BrowserFailure).code)
    }

    @Test fun plainTextIsRead() = runBlocking {
        server.enqueue(MockResponse().setBody("plain body").setHeader("Content-Type", "text/plain"))
        val doc = ReaderService().fetch(server.url("/plain").toString())
        assertTrue(doc.text.contains("plain body"))
        assertTrue(!doc.needsRenderer)
    }
}
