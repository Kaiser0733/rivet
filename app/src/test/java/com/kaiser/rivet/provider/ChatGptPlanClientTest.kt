package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChatGptPlanClientTest {

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

    private fun config(model: String = "gpt-5") = ProviderConfig(
        id = "test",
        type = ProviderType.ChatGptPlan,
        name = "test",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = model,
    )

    private fun client() = ChatGptPlanClient(config(), "test-token")

    // --- models list ---

    @Test fun listModelsParsesSiwcShape() {
        val body = """{"models":[
            {"slug":"gpt-5","display_name":"GPT-5","visibility":"list"},
            {"slug":"gpt-5-internal","display_name":"Internal","visibility":"internal"},
            {"slug":"o4-mini","display_name":"o4 mini","visibility":"list"}
        ]}"""
        val models = parseChatGptModels(body)
        assertEquals(2, models.size)
        assertEquals("gpt-5", models[0].id)
        assertEquals("GPT-5", models[0].label)
        assertEquals("o4-mini", models[1].id)
        assertEquals("o4 mini", models[1].label)
    }

    @Test fun listModelsRejectsDataShape() {
        try {
            parseChatGptModels("""{"data":[{"id":"x"}]}""")
            fail("expected InvalidResponse")
        } catch (e: ProviderError.InvalidResponse) {
            // the {"data":[]} parser must not be reused for the SIWC shape
        }
    }

    @Test fun listModelsSendsBearerToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        client().listModels()
        val recorded = server.takeRequest()
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.endsWith("/models"))
    }

    // --- SSE accumulator ---

    @Test fun streamAccumulatesTextDeltas() {
        val deltas = mutableListOf<String>()
        val stream = ResponsesAgentStream(deltas::add)
        stream.accept("""{"type":"response.output_text.delta","delta":"Hello"}""")
        stream.accept("""{"type":"response.output_text.delta","delta":" world"}""")
        stream.accept("""{"type":"response.completed","response":{"usage":{
            "input_tokens":10,"output_tokens":5,"total_tokens":15}}}""")
        val response = stream.response()
        assertEquals("Hello world", response.text)
        assertEquals(listOf("Hello", " world"), deltas)
        assertEquals(10L, response.usage?.inputTokens)
        assertEquals(5L, response.usage?.outputTokens)
        assertEquals(15L, response.usage?.totalTokens)
        assertTrue(response.toolCalls.isEmpty())
    }

    @Test fun streamFailedMapsUsageLimitCode() {
        val stream = ResponsesAgentStream {}
        stream.accept("""{"type":"response.failed","response":{"error":{
            "code":"subscription_sharing_usage_limit_exceeded","message":"cap"}}}""")
        try {
            stream.response()
            fail("expected UsageLimit")
        } catch (e: ProviderError.UsageLimit) {
            // dedicated mapping, not a generic ProviderMessage
        }
    }

    @Test fun streamFailedOtherCodeIsProviderMessage() {
        val stream = ResponsesAgentStream {}
        stream.accept("""{"type":"response.failed","response":{"error":{
            "code":"something_else","message":"boom"}}}""")
        try {
            stream.response()
            fail("expected ProviderError")
        } catch (e: ProviderError.ProviderMessage) {
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test fun streamIncompleteIsTerminal() {
        val stream = ResponsesAgentStream {}
        stream.accept("""{"type":"response.incomplete","response":{
            "incomplete_details":{"reason":"max_output_tokens"}}}""")
        try {
            stream.response()
            fail("expected IncompleteGeneration")
        } catch (e: ProviderError.IncompleteGeneration) {
            assertTrue(e.message!!.contains("max_output_tokens"))
        }
    }

    @Test fun eofWithoutTerminalIsInvalidResponse() {
        val stream = ResponsesAgentStream {}
        stream.accept("""{"type":"response.output_text.delta","delta":"partial"}""")
        try {
            stream.response()
            fail("expected InvalidResponse")
        } catch (e: ProviderError.InvalidResponse) {
            // no [DONE] sentinel on this stream; EOF without completed/failed
            // must not silently return a partial response
        }
    }

    @Test fun streamAccumulatesFunctionCalls() {
        val stream = ResponsesAgentStream {}
        stream.accept("""{"type":"response.output_item.added","output_index":0,
            "item":{"type":"function_call","id":"fc_1","call_id":"call_1",
            "name":"read_file","arguments":""}}""")
        stream.accept("""{"type":"response.function_call_arguments.delta",
            "output_index":0,"item_id":"fc_1","delta":"{\"path\":"}""")
        stream.accept("""{"type":"response.function_call_arguments.delta",
            "output_index":0,"item_id":"fc_1","delta":"\"a.txt\"}"}""")
        stream.accept("""{"type":"response.completed","response":{}}""")
        val response = stream.response()
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_1", response.toolCalls[0].id)
        assertEquals("read_file", response.toolCalls[0].name)
        assertEquals("""{"path":"a.txt"}""", response.toolCalls[0].arguments)
    }

    @Test fun streamSkipsNonFunctionCallItems() {
        val stream = ResponsesAgentStream {}
        stream.accept("""{"type":"response.output_item.added","output_index":0,
            "item":{"type":"reasoning","id":"rs_1"}}""")
        stream.accept("""{"type":"response.completed","response":{}}""")
        assertTrue(stream.response().toolCalls.isEmpty())
    }

    // --- end-to-end streamAgent over MockWebServer ---

    @Test fun streamAgentPostsResponsesEndpoint() = runTest {
        val sseBody = Buffer().apply {
            writeUtf8("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n")
            writeUtf8("data: {\"type\":\"response.completed\",\"response\":{}}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )
        val request = AgentRequest(
            model = "gpt-5",
            messages = listOf(AgentMessage.user("hello")),
            system = "sys",
            reasoning = ReasoningLevel.Default,
            tools = emptyList(),
        )
        val deltas = mutableListOf<String>()
        val response = client().streamAgent(request, deltas::add)
        assertEquals("hi", response.text)
        assertEquals(listOf("hi"), deltas)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/responses"))
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        val sent = recorded.body.readUtf8()
        assertTrue(sent.contains("\"model\":\"gpt-5\""))
        assertTrue(sent.contains("\"store\":false"))
        assertTrue(sent.contains("\"stream\":true"))
        assertTrue(sent.contains("\"instructions\":\"sys\""))
    }
}
