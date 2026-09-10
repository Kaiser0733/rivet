package com.kaiser.rivet.provider

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicClientTest {

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
        id = "t",
        type = ProviderType.Anthropic,
        name = "t",
        baseUrl = server.url("/").toString().trimEnd('/'),
        model = "claude-x",
    )

    @Test
    fun listModelsParsesIdAndDisplayName() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"claude-b","display_name":"Claude B"},{"id":"claude-a"}]}"""),
        )
        val models = AnthropicClient(config(), "key").listModels()
        assertEquals(listOf("claude-a", "claude-b"), models.map { it.id })
        assertEquals("Claude B", models[1].label)
    }

    @Test
    fun streamChatExtractsTextDeltas() = runTest {
        val sse = listOf(
            """{"type":"message_start","message":{}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"internal"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val full = AnthropicClient(config(), "key").streamChat(
            ChatRequest("claude-x", emptyList(), "sys", ReasoningLevel.Default),
        ) {}
        assertEquals("Hi there", full)

        val recorded = server.takeRequest()
        assertEquals("key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"system\":\"sys\""))
        assertTrue(body.contains("\"max_tokens\""))
    }

    @Test
    fun reasoningAddsThinkingBlock() = runTest {
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        AnthropicClient(config(), "key").streamChat(
            ChatRequest("claude-x", emptyList(), "", ReasoningLevel.High),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\""))
        assertTrue(body.contains("\"budget_tokens\""))
    }

    @Test
    fun defaultReasoningSendsNoThinkingBlock() = runTest {
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        AnthropicClient(config(), "key").streamChat(
            ChatRequest("claude-x", emptyList(), "", ReasoningLevel.Default),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("\"thinking\""))
    }

    @Test
    fun overloadErrorSurfacesProviderMessage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"message":"Overloaded"}}"""),
        )
        val result = AnthropicClient(config(), "key").testConnection()
        assertEquals(false, result.ok)
        assertTrue(result.message.isNotEmpty())
    }
}
