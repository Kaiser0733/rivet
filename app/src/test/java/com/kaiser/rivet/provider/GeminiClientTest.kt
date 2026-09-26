package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiClientTest {

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
        type = ProviderType.Gemini,
        name = "t",
        baseUrl = server.url("/").toString().trimEnd('/'),
        model = "gemini-x",
    )

    @Test
    fun listModelsFiltersNonGeneratable() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"models":[""" +
                    """{"name":"models/gemini-a","displayName":"Gemini A","supportedGenerationMethods":["generateContent"]},""" +
                    """{"name":"models/embedding-x","supportedGenerationMethods":["embedContent"]}]}""",
            ),
        )
        val models = GeminiClient(config(), "key").listModels()
        assertEquals(listOf("gemini-a"), models.map { it.id })
        assertEquals("Gemini A", models[0].label)
    }

    @Test
    fun listModelsKeepsReportedInputLimit() = runTest {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"],"inputTokenLimit":1048576}]}"""))
        assertEquals(1048576, GeminiClient(config(), "key").listModels().single().inputLimitTokens)
    }

    @Test
    fun listModelsRejectsOversizedResponse() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(MAX_PROVIDER_JSON_BODY_BYTES + 1)))
        try {
            GeminiClient(config(), "key").listModels()
            throw AssertionError("expected an oversized response to be rejected")
        } catch (_: ProviderError.ResponseTooLarge) {
            // expected
        }
    }

    @Test
    fun agentStreamConcatenatesParts() = runTest {
        val sse = listOf(
            """{"candidates":[{"content":{"parts":[{"text":"Hi "}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"there"},{"text":"!"}]},"finishReason":"STOP"}]}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val full = GeminiClient(config(), "key").streamAgent(
            AgentRequest("gemini-x", emptyList(), "sys", ReasoningLevel.High, emptyList()),
        ) {}
        assertEquals("Hi there!", full.text)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/v1beta/models/gemini-x:streamGenerateContent"))
        assertTrue(recorded.path!!.contains("alt=sse"))
        assertEquals("key", recorded.getHeader("x-goog-api-key"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(!body.contains("parametersJsonSchema"))
        assertTrue(!body.contains("reasoning"))
        assertTrue(!body.contains("thinking"))
        assertTrue(!body.contains("effort"))
    }

    @Test
    fun streamErrorChunkSurfacesMessage() = runTest {
        val sse = "data: {\"error\":{\"code\":429,\"message\":\"Resource exhausted\"}}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        try {
            GeminiClient(config(), "key").streamAgent(
                AgentRequest("gemini-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            throw AssertionError("expected ProviderMessage")
        } catch (e: ProviderError.ProviderMessage) {
            assertTrue(e.message!!.contains("Resource exhausted"))
        }
    }

    @Test
    fun nativeFunctionCallsAndResponsesPreserveIds() = runTest {
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[" +
            "{\"text\":\"Inspecting\"},{\"functionCall\":{\"id\":\"g-1\",\"name\":\"read_file\",\"args\":{\"path\":\"A.kt\"}},\"thoughtSignature\":\"sig\"}" +
            "]},\"finishReason\":\"STOP\"}]}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val client = GeminiClient(config(), "key")
        val response = client.streamAgent(AgentRequest(
            "gemini-x", emptyList(), "sys", ReasoningLevel.Default,
            listOf(AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })),
        )) {}
        assertEquals("Inspecting", response.text)
        assertEquals(AgentToolCall("g-1", "read_file", "{\"path\":\"A.kt\"}"), response.toolCalls.single())
        server.takeRequest()

        server.enqueue(MockResponse().setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"STOP\"}]}\n\n"))
        client.streamAgent(AgentRequest(
            "gemini-x",
            listOf(AgentMessage.assistant("", response.toolCalls, response.transportState), AgentMessage.tools(listOf(
                AgentToolResult("g-1", "read_file", "{\"text\":\"x\"}"),
            ))), "", ReasoningLevel.Default, emptyList(),
        )) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thoughtSignature\":\"sig\""))
        assertTrue(body.contains("\"functionResponse\":{\"id\":\"g-1\",\"name\":\"read_file\""))
    }

    @Test
    fun functionCallIsRejectedWhenCandidateHasNoFinishReason() = runTest {
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[" +
            "{\"functionCall\":{\"id\":\"g-1\",\"name\":\"read_file\",\"args\":{\"path\":\"A.kt\"}}}" +
            "]}}]}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        try {
            GeminiClient(config(), "key").streamAgent(AgentRequest(
                "gemini-x", emptyList(), "sys", ReasoningLevel.Default, emptyList(),
            )) {}
            throw AssertionError("Expected an incomplete stream to be rejected")
        } catch (error: ProviderError.InvalidResponse) {
            assertTrue(error.detail.contains("incomplete"))
        }
    }

    @Test
    fun functionCallsAreRejectedForNonSuccessFinishReasons() = runTest {
        for (reason in listOf("MALFORMED_FUNCTION_CALL", "SAFETY", "MAX_TOKENS")) {
            val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                "{\"functionCall\":{\"id\":\"g-1\",\"name\":\"delete_path\",\"args\":{\"path\":\"important.txt\"}}}" +
                "]},\"finishReason\":\"$reason\"}]}\n\n"
            server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

            try {
                GeminiClient(config(), "key").streamAgent(AgentRequest(
                    "gemini-x", emptyList(), "sys", ReasoningLevel.Default, emptyList(),
                )) {}
                throw AssertionError("$reason must not authorize a function call")
            } catch (error: ProviderError.IncompleteGeneration) {
                assertEquals(reason, error.reason)
            }
        }
    }

    @Test
    fun maxTokensTextIsNotPresentedAsCompleted() = runTest {
        val sse = """data: {"candidates":[{"content":{"parts":[{"text":"I changed the file"}]},"finishReason":"MAX_TOKENS"}]}

"""
        server.enqueue(MockResponse().setBody(sse))

        try {
            GeminiClient(config(), "key").streamAgent(
                AgentRequest("gemini-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
            ) {}
            throw AssertionError("A MAX_TOKENS answer must not be presented as complete")
        } catch (_: ProviderError) {
            // expected
        }
    }

    @Test
    fun toolSchemaUsesJsonSchemaFieldAndTextSignatureIsReturned() = runTest {
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[" +
            "{\"text\":\"\",\"thoughtSignature\":\"text-sig\"}," +
            "{\"functionCall\":{\"id\":\"g-1\",\"name\":\"list_directory\",\"args\":{}}}" +
            "]},\"finishReason\":\"STOP\"}]}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val client = GeminiClient(config(), "key")
        val definition = AgentToolDefinition(
            "list_directory", "List", buildJsonObject { put("type", "object"); put("additionalProperties", false) },
        )
        val response = client.streamAgent(AgentRequest(
            "gemini-x", emptyList(), "", ReasoningLevel.Default, listOf(definition),
        )) {}
        val firstBody = server.takeRequest().body.readUtf8()
        assertTrue(firstBody.contains("\"parametersJsonSchema\""))
        assertTrue(!firstBody.contains("\"parameters\":"))

        server.enqueue(MockResponse().setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"STOP\"}]}\n\n"))
        client.streamAgent(AgentRequest(
            "gemini-x", listOf(AgentMessage.assistant("", response.toolCalls, response.transportState)),
            "", ReasoningLevel.Default, emptyList(),
        )) {}
        assertTrue(server.takeRequest().body.readUtf8().contains("\"thoughtSignature\":\"text-sig\""))
    }

    @Test
    fun multipleFunctionCallsRemainDistinct() = runTest {
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[" +
            "{\"functionCall\":{\"id\":\"a\",\"name\":\"read_file\",\"args\":{\"path\":\"A.kt\"}}}," +
            "{\"functionCall\":{\"id\":\"b\",\"name\":\"read_file\",\"args\":{\"path\":\"B.kt\"}}}" +
            "]},\"finishReason\":\"STOP\"}]}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val response = GeminiClient(config(), "key").streamAgent(
            AgentRequest("gemini-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
        ) {}

        assertEquals(listOf("a", "b"), response.toolCalls.map { it.id })
        assertEquals(listOf("A.kt", "B.kt"), response.toolCalls.map {
            kotlinx.serialization.json.Json.parseToJsonElement(it.arguments).jsonObject["path"]!!.jsonPrimitive.content
        })
    }

    @Test fun usageMetadataIsKeptWhenFinalChunkHasNoCandidate() = runTest {
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"STOP\"}]}\n\n" +
            "data: {\"usageMetadata\":{\"promptTokenCount\":35,\"candidatesTokenCount\":8," +
            "\"cachedContentTokenCount\":4,\"thoughtsTokenCount\":2,\"totalTokenCount\":45}}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val response = GeminiClient(config(), "key").streamAgent(
            AgentRequest("gemini-x", emptyList(), "", ReasoningLevel.Default, emptyList())) {}
        assertEquals(35L, response.usage?.inputTokens)
        assertEquals(8L, response.usage?.outputTokens)
        assertEquals(4L, response.usage?.cacheReadTokens)
        assertEquals(2L, response.usage?.reasoningTokens)
        assertEquals(45L, response.usage?.totalTokens)
    }
}
