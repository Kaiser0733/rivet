package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        AnthropicClient(config("claude-haiku-4-5-20251001"), "key").streamChat(
            ChatRequest("claude-haiku-4-5-20251001", emptyList(), "", ReasoningLevel.High),
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
        AnthropicClient(config("claude-opus-4-9"), "key").streamChat(
            ChatRequest("claude-opus-4-9", emptyList(), "", ReasoningLevel.High),
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

    @Test
    fun streamedToolInputIsReconstructedAndResultUsesToolResultBlock() = runTest {
        val sse = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Inspecting"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-1","name":"read_file","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"A.kt\"}"}}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val client = AnthropicClient(config(), "key")
        val response = client.streamAgent(AgentRequest(
            "claude-x", emptyList(), "sys", ReasoningLevel.Default,
            listOf(AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })),
        )) {}
        assertEquals("Inspecting", response.text)
        assertEquals(AgentToolCall("tool-1", "read_file", "{\"path\":\"A.kt\"}"), response.toolCalls.single())
        server.takeRequest()

        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        client.streamAgent(AgentRequest(
            "claude-x",
            listOf(AgentMessage.assistant("", response.toolCalls), AgentMessage.tools(listOf(
                AgentToolResult("tool-1", "read_file", "{\"text\":\"x\"}"),
            ))), "", ReasoningLevel.Default, emptyList(),
        )) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"tool_result\",\"tool_use_id\":\"tool-1\""))
    }

    @Test
    fun multipleToolUsesRemainDistinct() = runTest {
        val sse = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"a","name":"read_file","input":{"path":"A.kt"}}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"b","name":"read_file","input":{"path":"B.kt"}}}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val response = AnthropicClient(config(), "key").streamAgent(
            AgentRequest("claude-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
        ) {}

        assertEquals(listOf("a", "b"), response.toolCalls.map { it.id })
        assertEquals(listOf("{\"path\":\"A.kt\"}", "{\"path\":\"B.kt\"}"),
            response.toolCalls.map { it.arguments })
    }

    @Test fun streamUsageUsesFinalCumulativeOutputAndInitialInput() = runTest {
        val sse = listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":25,"output_tokens":1,"cache_read_input_tokens":5}}}""",
            """{"type":"message_delta","usage":{"output_tokens":15}}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val response = AnthropicClient(config(), "key").streamAgent(
            AgentRequest("claude-x", emptyList(), "", ReasoningLevel.Default, emptyList())) {}
        assertEquals(25L, response.usage?.inputTokens)
        assertEquals(15L, response.usage?.outputTokens)
        assertEquals(5L, response.usage?.cacheReadTokens)
    }

    @Test
    fun streamErrorSurfacesProviderMessage() = runTest {
        server.enqueue(MockResponse().setBody(
            "data: {\"type\":\"error\",\"error\":{\"message\":\"Overloaded mid-stream\"}}\n\n",
        ).setHeader("Content-Type", "text/event-stream"))

        try {
            AnthropicClient(config(), "key").streamAgent(
                AgentRequest("claude-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            throw AssertionError("expected ProviderMessage")
        } catch (e: ProviderError.ProviderMessage) {
            assertTrue(e.text.contains("Overloaded"))
        }
    }
}
