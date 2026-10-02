package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
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
    fun listModelsKeepsOnlyPositiveReportedInputLimit() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"large","max_input_tokens":200000,"max_tokens":128000,"capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true},"enabled":{"supported":false}}}}},{"id":"unknown","max_input_tokens":0}]}""",
        ))
        val models = AnthropicClient(config(), "key").listModels().associateBy { it.id }
        assertEquals(200000, models["large"]?.inputLimitTokens)
        assertEquals(null, models["unknown"]?.inputLimitTokens)
        assertEquals(128000, models["large"]?.anthropicMetadata?.maxOutputTokens)
        assertEquals(true, models["large"]?.anthropicMetadata?.thinkingSupported)
        assertEquals(true, models["large"]?.anthropicMetadata?.adaptiveThinkingSupported)
        assertEquals(false, models["large"]?.anthropicMetadata?.manualThinkingSupported)
        assertEquals(config().baseUrl, models["large"]?.anthropicMetadata?.baseUrl)
    }

    @Test
    fun listedAdaptiveMetadataSetsActualMaxTokensAndLeavesFullProviderHeadroom() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"claude-opus-5","max_input_tokens":1000000,"max_tokens":128000,"capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true},"enabled":{"supported":false}}}}}]}""",
        ))
        val listed = AnthropicClient(config(), "key").listModels().single()
        server.takeRequest()
        val selected = config().selectListedModel(listed).copy(reasoning = ReasoningLevel.High)
        assertEquals(AnthropicThinkingMode.Adaptive, anthropicThinkingMode(selected))
        assertEquals(128_000, anthropicOutputCeiling(selected))

        server.enqueue(MockResponse().setBody(finished("end_turn")))
        AnthropicClient(selected, "key").streamAgent(
            AgentRequest(selected.model, emptyList(), "", ReasoningLevel.High, emptyList()),
        ) {}

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"max_tokens\":128000"))
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"))
    }

    @Test
    fun smallListedAdaptiveWindowBoundsActualMaxTokensByQuarterItsInputLimit() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"claude-small-adaptive","max_input_tokens":4096,"max_tokens":128000,"capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true}}}}}]}""",
        ))
        val listed = AnthropicClient(config(), "key").listModels().single()
        server.takeRequest()
        val selected = config().selectListedModel(listed).copy(reasoning = ReasoningLevel.High)

        assertEquals(1_024, anthropicOutputCeiling(selected))
        server.enqueue(MockResponse().setBody(finished("end_turn")))
        AnthropicClient(selected, "key").streamAgent(
            AgentRequest(selected.model, emptyList(), "", ReasoningLevel.High, emptyList()),
        ) {}

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"))
    }

    @Test
    fun manualThinkingFitsReportedMaximumAndKeepsResponseHeadroom() = runTest {
        val cfg = config("claude-sonnet-4-5").copy(
            reasoning = ReasoningLevel.High,
            anthropicModelMetadata = AnthropicModelMetadata(
                model = "claude-sonnet-4-5",
                baseUrl = config().baseUrl,
                maxOutputTokens = 20_000,
                thinkingSupported = true,
                adaptiveThinkingSupported = false,
                manualThinkingSupported = true,
            ),
        )
        assertEquals(20_000, anthropicOutputCeiling(cfg))
        assertEquals(11_808, anthropicThinkingBudget(cfg))

        server.enqueue(MockResponse().setBody(finished("end_turn")))
        AnthropicClient(cfg, "key").streamAgent(
            AgentRequest(cfg.model, emptyList(), "", ReasoningLevel.High, emptyList()),
        ) {}

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"max_tokens\":20000"))
        assertTrue(body.contains("\"budget_tokens\":11808"))
        assertTrue(anthropicThinkingBudget(cfg) < anthropicOutputCeiling(cfg))
        assertEquals(8_192, anthropicOutputCeiling(cfg) - anthropicThinkingBudget(cfg))
    }

    @Test
    fun manualThinkingWithTooSmallWindowFailsBeforeSendingARequest() = runTest {
        val base = config("claude-sonnet-4-5")
        val cfg = base.copy(
            reasoning = ReasoningLevel.High,
            modelContextLimit = ModelContextLimit(base.model, base.baseUrl, 4_096),
            anthropicModelMetadata = AnthropicModelMetadata(
                model = base.model,
                baseUrl = base.baseUrl,
                maxOutputTokens = 128_000,
                thinkingSupported = true,
                adaptiveThinkingSupported = false,
                manualThinkingSupported = true,
            ),
        )

        try {
            AnthropicClient(cfg, "key").streamAgent(
                AgentRequest(cfg.model, emptyList(), "", ReasoningLevel.High, emptyList()),
            ) {}
            throw AssertionError("manual thinking that cannot fit must be rejected")
        } catch (_: ProviderError.UnsupportedConfiguration) {
            Unit
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun listedModelOnSecondPageKeepsItsInputLimit() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"first"}],"has_more":true,"last_id":"first"}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"target","max_input_tokens":200000}],"has_more":false}"""))

        val models = AnthropicClient(config(), "key").listModels().associateBy { it.id }

        assertEquals(200000, models["target"]?.inputLimitTokens)
        assertEquals(2, models.size)
        assertEquals("1000", server.takeRequest().requestUrl?.queryParameter("limit"))
        val second = server.takeRequest().requestUrl
        assertEquals("1000", second?.queryParameter("limit"))
        assertEquals("first", second?.queryParameter("after_id"))
    }

    @Test
    fun listModelsRejectsOversizedResponse() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(MAX_PROVIDER_JSON_BODY_BYTES + 1)))
        try {
            AnthropicClient(config(), "key").listModels()
            throw AssertionError("expected an oversized response to be rejected")
        } catch (_: ProviderError.ResponseTooLarge) {
            // expected
        }
    }

    @Test
    fun deeplyNestedStreamedToolArgumentsFailBeforeRecursiveParse() = runTest {
        val nested = "[".repeat(10_000) + "0" + "]".repeat(10_000)
        val delta = buildJsonObject {
            put("type", "content_block_delta")
            put("index", 0)
            put("delta", buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", nested)
            })
        }
        val sse = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"deep","name":"read_file","input":{}}}""",
            delta.toString(),
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        try {
            AnthropicClient(config(), "key").streamAgent(AgentRequest(
                "claude-x", emptyList(), "", ReasoningLevel.Default,
                listOf(AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })),
            )) {}
            throw AssertionError("Expected invalid tool input")
        } catch (_: ProviderError.InvalidResponse) { Unit }
    }

    @Test
    fun agentStreamExtractsTextDeltas() = runTest {
        val sse = listOf(
            """{"type":"message_start","message":{}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"internal"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val full = AnthropicClient(config(), "key").streamAgent(
            AgentRequest("claude-x", emptyList(), "sys", ReasoningLevel.Default, emptyList()),
        ) {}
        assertEquals("Hi there", full.text)

        val recorded = server.takeRequest()
        assertEquals("key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"system\":\"sys\""))
        assertTrue(body.contains("\"max_tokens\""))
    }

    @Test
    fun olderModelUsesManualThinkingBudget() = runTest {
        server.enqueue(MockResponse().setBody(finished("end_turn")))
        val cfg = withThinking(config("claude-haiku-4-5-20251001"), adaptive = false)
        AnthropicClient(cfg, "key").streamAgent(
            AgentRequest("claude-haiku-4-5-20251001", emptyList(), "", ReasoningLevel.High, emptyList()),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":32768}"))
        assertTrue(!body.contains("output_config"))
        assertTrue(body.contains("\"max_tokens\":40960"))
    }

    @Test
    fun currentModelUsesAdaptiveThinkingAndEffort() = runTest {
        server.enqueue(MockResponse().setBody(finished("end_turn")))
        val cfg = withThinking(config("claude-opus-4-8"), adaptive = true)
        AnthropicClient(cfg, "key").streamAgent(
            AgentRequest("claude-opus-4-8", emptyList(), "", ReasoningLevel.High, emptyList()),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"))
        assertTrue(body.contains("\"output_config\":{\"effort\":\"high\"}"))
        assertTrue(!body.contains("budget_tokens"))
    }

    @Test
    fun unknownModelOmitsReasoningConfiguration() = runTest {
        server.enqueue(MockResponse().setBody(finished("end_turn")))
        AnthropicClient(config("claude-opus-4-9"), "key").streamAgent(
            AgentRequest("claude-opus-4-9", emptyList(), "", ReasoningLevel.High, emptyList()),
        ) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("\"thinking\""))
        assertTrue(!body.contains("output_config"))
        assertTrue(body.contains("\"max_tokens\":8192"))
    }

    @Test
    fun defaultReasoningSendsNoThinkingBlock() = runTest {
        server.enqueue(MockResponse().setBody(finished("end_turn")))
        AnthropicClient(config(), "key").streamAgent(
            AgentRequest("claude-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
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
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            """{"type":"message_stop"}""",
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

        server.enqueue(MockResponse().setBody(finished("end_turn")))
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
    fun toolUseIsRejectedWhenStreamEndsBeforeMessageStop() = runTest {
        val sse = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"read_file","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        try {
            AnthropicClient(config(), "key").streamAgent(AgentRequest(
                "claude-x", emptyList(), "sys", ReasoningLevel.Default, emptyList(),
            )) {}
            throw AssertionError("Expected an incomplete stream to be rejected")
        } catch (error: ProviderError.InvalidResponse) {
            assertTrue(error.detail.contains("incomplete"))
        }
    }

    @Test
    fun completeLookingToolUseIsRejectedWhenGenerationHitsMaxTokens() = runTest {
        val sse = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"delete_path","input":{"path":"Max.txt"}}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse))

        try {
            AnthropicClient(config(), "key").streamAgent(
                AgentRequest("claude-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            throw AssertionError("A max_tokens tool decision must not be executed")
        } catch (_: ProviderError) {
            // expected
        }
    }

    @Test
    fun maxTokensTextIsNotPresentedAsCompleted() = runTest {
        val sse = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"I changed the file"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse))

        try {
            AnthropicClient(config(), "key").streamAgent(
                AgentRequest("claude-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            throw AssertionError("A max_tokens answer must not be presented as complete")
        } catch (_: ProviderError) {
            // expected
        }
    }

    @Test
    fun multipleToolUsesRemainDistinct() = runTest {
        val sse = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"a","name":"read_file","input":{"path":"A.kt"}}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"b","name":"read_file","input":{"path":"B.kt"}}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            """{"type":"message_stop"}""",
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
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val response = AnthropicClient(config(), "key").streamAgent(
            AgentRequest("claude-x", emptyList(), "", ReasoningLevel.Default, emptyList())) {}
        assertEquals(25L, response.usage?.inputTokens)
        assertEquals(15L, response.usage?.outputTokens)
        assertEquals(5L, response.usage?.cacheReadTokens)
        assertEquals(30L, response.usage?.contextInputTokens(ProviderType.Anthropic))
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

    @Test fun officialNativeCacheControlPreservesThinkingToolsAndOrderedContext() {
        val base = config("claude-sonnet-4-5").copy(baseUrl = "https://api.anthropic.com/v1")
        val call = AgentToolCall("read", "read_file", "{\"path\":\"A.kt\"}")
        val tool = AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })
        val context = com.kaiser.rivet.agent.InternalContext.summary("Untrusted task notes", "tree")
        val messages = listOf(context, AgentMessage.user("Inspect"),
            AgentMessage.assistant("", listOf(call), """{"provider":"anthropic","blocks":[{"type":"thinking","thinking":"private reasoning","signature":"sig"}]}"""),
            AgentMessage.tools(listOf(AgentToolResult("read", "read_file", "{}"))))
        for (adaptive in listOf(false, true)) {
            val selected = withThinking(base, adaptive)
            val body = AnthropicClient(selected, "not-sent").requestBody(
                AgentRequest(selected.model, messages, "Policy", ReasoningLevel.High, listOf(tool)))
            assertEquals("{\"type\":\"ephemeral\"}", body["cache_control"].toString())
            assertEquals(if (adaptive) "adaptive" else "enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("Policy", body["system"]!!.jsonPrimitive.content)
            assertEquals("read_file", body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
            val wire = body["messages"]!!.jsonArray
            assertEquals(listOf("user", "user", "assistant", "user"), wire.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("Inspect", wire[1].jsonObject["content"]!!.jsonPrimitive.content)
            val assistant = wire[2].jsonObject["content"]!!.jsonArray
            assertEquals("sig", assistant[0].jsonObject["signature"]!!.jsonPrimitive.content)
            assertEquals("tool_use", assistant[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("read", wire[3].jsonObject["content"]!!.jsonArray[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
            assertFalse(body.toString().contains("internalContext"))
            assertFalse(body.toString().contains("workspaceId"))
            assertFalse(body.toString().contains("not-sent"))
        }
    }

    @Test fun cacheControlIsOmittedForUnknownProxyOrUnsupportedConfiguration() {
        val native = config().copy(baseUrl = "https://api.anthropic.com")
        for (candidate in listOf(config(), native.copy(type = ProviderType.OpenAiCompatible),
                native.copy(baseUrl = "http://api.anthropic.com"), native.copy(baseUrl = "https://proxy.invalid"),
                native.copy(baseUrl = "https://api.anthropic.com/other"), native.copy(model = "unknown"))) {
            val body = AnthropicClient(candidate, "key").requestBody(
                AgentRequest(candidate.model, listOf(AgentMessage.user("Hello")), "Policy", ReasoningLevel.Default, emptyList()))
            assertFalse(body.containsKey("cache_control"))
        }
        val body = AnthropicClient(native, "key").requestBody(
            AgentRequest(native.model, emptyList(), "", ReasoningLevel.Default, emptyList()))
        assertFalse(body.containsKey("thinking"))
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    private fun finished(reason: String) =
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$reason\"}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n"

    private fun withThinking(config: ProviderConfig, adaptive: Boolean) = config.copy(
        anthropicModelMetadata = AnthropicModelMetadata(
            model = config.model,
            baseUrl = config.baseUrl,
            maxOutputTokens = 64_000,
            thinkingSupported = true,
            adaptiveThinkingSupported = adaptive,
            manualThinkingSupported = !adaptive,
        ),
    )
}
