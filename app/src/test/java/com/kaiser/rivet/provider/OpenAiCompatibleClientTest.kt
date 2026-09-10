package com.kaiser.rivet.provider

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config() = ProviderConfig(
        id = "test",
        type = ProviderType.OpenAiCompatible,
        name = "test",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = "test-model",
    )

    @Test
    fun listModelsParsesIds() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"m-b"},{"id":"m-a"}]}"""),
        )
        val models = OpenAiCompatibleClient(config(), "key").listModels()
        assertEquals(listOf("m-a", "m-b"), models.map { it.id })
    }

    @Test
    fun listModelsEmptyAndMalformed() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertEquals(emptyList<ModelInfo>(), OpenAiCompatibleClient(config(), "key").listModels())

        server.enqueue(MockResponse().setBody("""{"data":[{"weird":true}]}"""))
        assertEquals(emptyList<ModelInfo>(), OpenAiCompatibleClient(config(), "key").listModels())

        server.enqueue(MockResponse().setBody("not json"))
        try {
            OpenAiCompatibleClient(config(), "key").listModels()
            throw AssertionError("expected InvalidResponse")
        } catch (e: ProviderError.InvalidResponse) {
            // expected
        }
    }

    @Test
    fun streamChatAccumulatesDeltas() = runTest {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val out = StringBuilder()
        val full = OpenAiCompatibleClient(config(), "key").streamChat(
            ChatRequest("test-model", emptyList(), "sys", ReasoningLevel.Default),
        ) { out.append(it) }
        assertEquals("Hello", full)
        assertEquals("Hello", out.toString())

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun streamChatSendsReasoningEffort() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
        OpenAiCompatibleClient(config(), "key").streamChat(
            ChatRequest("test-model", emptyList(), "", ReasoningLevel.High),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
    }

    @Test
    fun streamChatSendsAuthAndCustomHeaders() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
        val cfg = config().copy(
            headers = listOf(
                ProviderHeader("X-Custom", "yes"),
                // Free-form headers may never override transport auth.
                ProviderHeader("Authorization", "Bearer evil"),
                ProviderHeader("x-api-key", "evil"),
            ),
        )
        OpenAiCompatibleClient(cfg, "key").streamChat(
            ChatRequest("test-model", emptyList(), "", ReasoningLevel.Default),
        ) {}
        val recorded = server.takeRequest()
        assertEquals("Bearer key", recorded.getHeader("Authorization"))
        assertEquals("yes", recorded.getHeader("X-Custom"))
        assertEquals(null, recorded.getHeader("x-api-key"))
    }

    @Test
    fun unauthorizedMapsToAuthenticationError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val result = OpenAiCompatibleClient(config(), "key").testConnection()
        assertEquals(false, result.ok)
        assertTrue(result.message.contains("Authentication failed"))
    }

    @Test
    fun rateLimitMapsToRateLimitMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        val result = OpenAiCompatibleClient(config(), "key").testConnection()
        assertEquals(false, result.ok)
        assertTrue(result.message.contains("Rate limited"))
    }

    @Test
    fun missingModelsEndpointFallsBackToManual() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = OpenAiCompatibleClient(config(), "key").testConnection()
        assertEquals(false, result.ok)
        assertTrue(result.message.contains("manually"))
    }
}
