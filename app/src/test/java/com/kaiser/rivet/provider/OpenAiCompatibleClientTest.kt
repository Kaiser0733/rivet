package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentLoop
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.PreparedAgentTool
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
    fun openRouterModelLimitIsScopedToItsProvider() = runTest {
        val listing = """{"data":[{"id":"m","context_length":32000,"top_provider":{"context_length":16000}}]}"""
        server.enqueue(MockResponse().setBody(listing))
        val routed = OpenAiCompatibleClient(config(ProviderType.OpenRouter), "key").listModels().single()
        assertEquals(16000, routed.inputLimitTokens)

        server.enqueue(MockResponse().setBody(listing))
        val custom = OpenAiCompatibleClient(config(ProviderType.OpenAiCompatible), "key").listModels().single()
        assertEquals(null, custom.inputLimitTokens)
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
    fun listModelsRejectsOversizedResponse() = runTest {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(MAX_PROVIDER_JSON_BODY_BYTES + 1), 8192))
        try {
            OpenAiCompatibleClient(config(), "key").listModels()
            fail("expected an oversized response to be rejected")
        } catch (_: ProviderError.ResponseTooLarge) {
            // expected
        }
    }

    @Test
    fun agentStreamAccumulatesDeltas() = runTest {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val out = StringBuilder()
        val full = OpenAiCompatibleClient(config(), "key").streamAgent(
            AgentRequest("test-model", emptyList(), "sys", ReasoningLevel.Default, emptyList()),
        ) { out.append(it) }
        assertEquals("Hello", full.text)
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
        OpenAiCompatibleClient(config(), "key").streamAgent(
            AgentRequest("test-model", emptyList(), "", ReasoningLevel.High, emptyList()),
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
        ).streamAgent(
            AgentRequest("gpt-5", emptyList(), "", ReasoningLevel.High, emptyList()),
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
        ).streamAgent(
            AgentRequest("test-model", emptyList(), "", ReasoningLevel.Max, emptyList()),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"xhigh\""))
    }

    @Test
    fun agentStreamSendsAuthAndCustomHeaders() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
        val cfg = config().copy(
            headers = listOf(
                ProviderHeader("X-Custom", "yes"),
                // Free-form headers may never override transport auth.
                ProviderHeader("Authorization", "Bearer evil"),
                ProviderHeader("x-api-key", "evil"),
            ),
        )
        OpenAiCompatibleClient(cfg, "key").streamAgent(
            AgentRequest("test-model", emptyList(), "", ReasoningLevel.Default, emptyList()),
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
        ).joinToString("") { "data: $it\n\n" } +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"
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

    @Test
    fun completeLookingToolCallIsRejectedWhenGenerationHitsLengthLimit() = runTest {
        val tool = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"delete_path","arguments":"{\"path\":\"Max.txt\"}"}}]}}]}"""
        val limited = """{"choices":[{"delta":{},"finish_reason":"length"}]}"""
        server.enqueue(MockResponse().setBody("data: $tool\n\ndata: $limited\n\ndata: [DONE]\n\n"))

        try {
            OpenAiCompatibleClient(config(), "key").streamAgent(
                AgentRequest("test-model", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            fail("A length-limited generation must not return a tool call")
        } catch (error: ProviderError.IncompleteGeneration) {
            assertEquals("length", error.reason)
            assertTrue(error.text().contains("stopped before finishing"))
        }
    }

    @Test
    fun lengthLimitedTextIsNotPresentedAsCompleted() = runTest {
        val text = """{"choices":[{"delta":{"content":"I changed the file"}}]}"""
        val limited = """{"choices":[{"delta":{},"finish_reason":"length"}]}"""
        server.enqueue(MockResponse().setBody("data: $text\n\ndata: $limited\n\ndata: [DONE]\n\n"))

        try {
            OpenAiCompatibleClient(config(), "key").streamAgent(
                AgentRequest("test-model", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            fail("A length-limited answer must not be presented as complete")
        } catch (_: ProviderError) {
            // expected
        }
    }

    @Test
    fun customToolCallWithoutFinishReasonNeverReachesApprovalOrExecution() = runTest {
        val tool = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"delete_path","arguments":"{\"path\":\"Max.txt\"}"}}]}}]}"""
        server.enqueue(MockResponse().setBody("data: $tool\n\ndata: [DONE]\n\n"))
        val client = OpenAiCompatibleClient(config(), "key")
        var prepared = 0
        var approvals = 0
        var executions = 0
        val loop = AgentLoop(
            requestModel = { messages, tools, onText ->
                client.streamAgent(AgentRequest("test-model", messages, "", ReasoningLevel.Default, tools), onText)
            },
            prepareTool = { call ->
                prepared++
                PreparedAgentTool(call, AgentApprovalRequest(call, "Delete", "Max.txt")) {
                    executions++
                    AgentToolResult(call.id, call.name, "{}")
                }
            },
            requestApproval = { approvals++; true },
        )

        try {
            loop.run(listOf(AgentMessage.user("Delete Max.txt")), emptyList())
            fail("An unconfirmed tool decision must not reach the agent loop")
        } catch (_: ProviderError.IncompleteGeneration) {
            // expected
        }
        assertEquals(0, prepared)
        assertEquals(0, approvals)
        assertEquals(0, executions)
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
