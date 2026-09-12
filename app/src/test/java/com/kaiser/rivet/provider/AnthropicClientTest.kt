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

    private fun config(model: String = "claude-x") = ProviderConfig(
        id = "t",
        type = ProviderType.Anthropic,
        name = "t",
        baseUrl = server.url("/").toString().trimEnd('/'),
        model = model,
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
    fun olderModelUsesManualThinkingBudget() = runTest {
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        AnthropicClient(config("claude-3-7-sonnet-latest"), "key").streamChat(
            ChatRequest("claude-3-7-sonnet-latest", emptyList(), "", ReasoningLevel.High),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":32768}"))
        assertTrue(!body.contains("output_config"))
        assertTrue(body.contains("\"max_tokens\":40960"))
    }

    @Test
    fun currentModelUsesAdaptiveThinkingAndEffort() = runTest {
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        AnthropicClient(config("claude-opus-4-8"), "key").streamChat(
            ChatRequest("claude-opus-4-8", emptyList(), "", ReasoningLevel.High),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"))
        assertTrue(body.contains("\"output_config\":{\"effort\":\"high\"}"))
        assertTrue(!body.contains("budget_tokens"))
    }

    @Test
    fun unknownModelOmitsReasoningConfiguration() = runTest {
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        AnthropicClient(config("claude-unknown"), "key").streamChat(
            ChatRequest("claude-unknown", emptyList(), "", ReasoningLevel.High),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("\"thinking\""))
        assertTrue(!body.contains("output_config"))
        assertTrue(body.contains("\"max_tokens\":8192"))
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
