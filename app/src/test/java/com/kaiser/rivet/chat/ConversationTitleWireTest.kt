package com.kaiser.rivet.chat

import com.kaiser.rivet.provider.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleWireTest {
    @Test fun isolatedTitleEndsInUserInputAcrossProviderProtocolsWithoutTools() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            for (type in listOf(ProviderType.OpenAiCompatible, ProviderType.Anthropic, ProviderType.Gemini)) {
                val events = when (type) {
                    ProviderType.Anthropic -> "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Fix Login Crash\"}}\n\n" +
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}\n\n" +
                        "data: {\"type\":\"message_stop\"}\n\n"
                    ProviderType.Gemini -> "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Fix Login Crash\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                    else -> "data: {\"choices\":[{\"delta\":{\"content\":\"Fix Login Crash\"},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events))
                val config = ProviderConfig("p", type, "Test", server.url("/v1").toString(), "model")
                val request = ConversationTitle.request(config.model, "Fix the login crash", "Done")
                assertEquals("Fix Login Crash", providerClient(config, "test-key").streamAgent(request) {}.text)
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertFalse(body.containsKey("tools"))
                assertFalse(body.containsKey("thinking"))
                val messages = body[if (type == ProviderType.Gemini) "contents" else "messages"]!!.jsonArray
                assertEquals("user", messages.last().jsonObject["role"]!!.jsonPrimitive.content)
                assertEquals(1, messages.size)
            }
        } finally { server.shutdown() }
    }
}
