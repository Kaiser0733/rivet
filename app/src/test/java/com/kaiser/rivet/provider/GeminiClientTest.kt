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
    fun streamChatConcatenatesParts() = runTest {
        val sse = listOf(
            """{"candidates":[{"content":{"parts":[{"text":"Hi "}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"there"},{"text":"!"}]}}]}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val full = GeminiClient(config(), "key").streamChat(
            ChatRequest("gemini-x", emptyList(), "sys", ReasoningLevel.High),
        ) {}
        assertEquals("Hi there!", full)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/v1beta/models/gemini-x:streamGenerateContent"))
        assertTrue(recorded.path!!.contains("alt=sse"))
        assertEquals("key", recorded.getHeader("x-goog-api-key"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(!body.contains("reasoning"))
        assertTrue(!body.contains("thinking"))
        assertTrue(!body.contains("effort"))
    }

    @Test
    fun streamErrorChunkSurfacesMessage() = runTest {
        val sse = "data: {\"error\":{\"code\":429,\"message\":\"Resource exhausted\"}}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        try {
            GeminiClient(config(), "key").streamChat(
                ChatRequest("gemini-x", emptyList(), "", ReasoningLevel.Default),
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
            "]}}]}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val client = GeminiClient(config(), "key")
        val response = client.streamAgent(AgentRequest(
            "gemini-x", emptyList(), "sys", ReasoningLevel.Default,
            listOf(AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })),
        )) {}
        assertEquals("Inspecting", response.text)
        assertEquals(AgentToolCall("g-1", "read_file", "{\"path\":\"A.kt\"}"), response.toolCalls.single())
        server.takeRequest()

        server.enqueue(MockResponse().setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]}}]}\n\n"))
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
    fun multipleFunctionCallsRemainDistinct() = runTest {
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[" +
            "{\"functionCall\":{\"id\":\"a\",\"name\":\"read_file\",\"args\":{\"path\":\"A.kt\"}}}," +
            "{\"functionCall\":{\"id\":\"b\",\"name\":\"read_file\",\"args\":{\"path\":\"B.kt\"}}}" +
            "]}}]}\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val response = GeminiClient(config(), "key").streamAgent(
            AgentRequest("gemini-x", emptyList(), "", ReasoningLevel.Default, emptyList()),
        ) {}

        assertEquals(listOf("a", "b"), response.toolCalls.map { it.id })
        assertEquals(listOf("A.kt", "B.kt"), response.toolCalls.map {
            kotlinx.serialization.json.Json.parseToJsonElement(it.arguments).jsonObject["path"]!!.jsonPrimitive.content
        })
    }
}
