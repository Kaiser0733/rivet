package com.kaiser.rivet.browser

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderServiceHttpTest {
    @Test fun readerUsesABrowserUserAgentOnInitialRequestAndRedirect() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/article"))
            server.enqueue(MockResponse().setBody("An article for the reader."))

            val page = ReaderService().fetchPage(server.url("/start").toString())

            assertEquals(200, page.httpStatus)
            repeat(2) {
                val agent = server.takeRequest().getHeader("User-Agent").orEmpty()
                assertTrue(agent.startsWith("Mozilla/5.0"))
                assertTrue(agent.contains("Chrome/"))
                assertFalse(agent.contains("okhttp", ignoreCase = true))
            }
        }
    }

    @Test fun aForbiddenReaderRequestHasItsOwnErrorKind() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))

            val error = runCatching { ReaderService().fetchPage(server.url("/article").toString()) }
                .exceptionOrNull() as BrowserFailure

            assertEquals("browser_reader_http", error.code)
            assertTrue(error.message.contains("reader request"))
            assertTrue(error.message.contains("HTTP 403"))
        }
    }
}
