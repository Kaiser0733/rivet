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
import org.junit.Assert.fail
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

    private fun config(
        type: ProviderType = ProviderType.OpenAiCompatible,
        model: String = "test-model",
    ) = ProviderConfig(
        id = "test",
        type = type,
        name = "test",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = model,
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
    fun genericProviderOmitsReasoningEffort() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
        OpenAiCompatibleClient(config(), "key").streamChat(
            ChatRequest("test-model", emptyList(), "", ReasoningLevel.High),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("reasoning_effort"))
    }

    @Test
    fun openAiReasoningModelSendsReasoningEffort() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
        OpenAiCompatibleClient(
            config(ProviderType.OpenAi, "gpt-5"),
            "key",
        ).streamChat(
            ChatRequest("gpt-5", emptyList(), "", ReasoningLevel.High),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
    }

    @Test
    fun openRouterSendsMaxReasoningEffort() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
        OpenAiCompatibleClient(
            config(ProviderType.OpenRouter),
            "key",
        ).streamChat(
            ChatRequest("test-model", emptyList(), "", ReasoningLevel.Max),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"xhigh\""))
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

    @Test
    fun agentToolsAndResultsUseChatCompletionsFormat() = runTest {
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\ndata: [DONE]\n\n"))
        val call = AgentToolCall("call-1", "read_file", "{\"path\":\"A.kt\"}")
        OpenAiCompatibleClient(config(), "key").streamAgent(AgentRequest(
            "test-model",
            listOf(AgentMessage.assistant("", listOf(call)), AgentMessage.tools(listOf(
                AgentToolResult("call-1", "read_file", "{\"text\":\"x\"}"),
            ))),
            "sys", ReasoningLevel.Default,
            listOf(AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })),
        )) {}

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tools\":[{\"type\":\"function\""))
        assertTrue(body.contains("\"tool_calls\":[{\"id\":\"call-1\""))
        assertTrue(body.contains("\"role\":\"tool\",\"tool_call_id\":\"call-1\""))
    }

    @Test
    fun streamedToolFragmentsAndMultipleCallsAreReconstructed() = runTest {
        val sse = listOf(
            """{"choices":[{"delta":{"content":"Checking ","tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"read_","arguments":"{\"pa"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"search_files","arguments":"{\"query\":\"x\"}"}},{"index":0,"function":{"name":"file","arguments":"th\":\"A.kt\"}"}}]}}]}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val response = OpenAiCompatibleClient(config(), "key").streamAgent(
            AgentRequest("test-model", emptyList(), "", ReasoningLevel.Default, emptyList()),
        ) {}

        assertEquals("Checking ", response.text)
        assertEquals(listOf("a", "b"), response.toolCalls.map { it.id })
        assertEquals("{\"path\":\"A.kt\"}", response.toolCalls[0].arguments)
        assertEquals("read_file", response.toolCalls[0].name)
        assertEquals("search_files", response.toolCalls[1].name)
    }

    @Test
    fun toolCallIsRejectedWhenStreamEndsBeforeDoneMarker() = runTest {
        val payload = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
            "{\"index\":0,\"id\":\"call-1\",\"function\":{" +
            "\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"A.kt\\\"}\"}}]}}]}\n\n"
        server.enqueue(MockResponse().setBody(payload).setHeader("Content-Type", "text/event-stream"))

        try {
            OpenAiCompatibleClient(config(), "key").streamAgent(
                AgentRequest("test-model", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            fail("Expected an incomplete stream to be rejected")
        } catch (error: ProviderError.InvalidResponse) {
            assertTrue(error.detail.contains("incomplete"))
        }
    }

    @Test fun knownProvidersRequestAndParseFinalUsageButCustomShapeStaysBaseline() = runTest {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":12," +
            "\"total_tokens\":52,\"prompt_tokens_details\":{\"cached_tokens\":10}," +
            "\"completion_tokens_details\":{\"reasoning_tokens\":3}}}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val reported = OpenAiCompatibleClient(config(ProviderType.OpenAi), "key").streamAgent(
            AgentRequest("model", emptyList(), "", ReasoningLevel.Default, emptyList())) {}
        assertEquals(40L, reported.usage?.inputTokens)
        assertEquals(12L, reported.usage?.outputTokens)
        assertEquals(10L, reported.usage?.cacheReadTokens)
        assertEquals(3L, reported.usage?.reasoningTokens)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"include_usage\":true"))

        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        OpenAiCompatibleClient(config(), "key").streamAgent(
            AgentRequest("model", emptyList(), "", ReasoningLevel.Default, emptyList())) {}
        assertTrue(!server.takeRequest().body.readUtf8().contains("stream_options"))
    }

    @Test
    fun streamErrorSurfacesProviderMessage() = runTest {
        server.enqueue(MockResponse().setBody(
            "data: {\"error\":{\"message\":\"Tools are unsupported\"}}\n\n",
        ).setHeader("Content-Type", "text/event-stream"))

        try {
            OpenAiCompatibleClient(config(), "key").streamAgent(
                AgentRequest("test-model", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            throw AssertionError("expected ProviderMessage")
        } catch (e: ProviderError.ProviderMessage) {
            assertTrue(e.text.contains("unsupported"))
        }
    }
}
