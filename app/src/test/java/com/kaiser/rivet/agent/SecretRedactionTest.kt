package com.kaiser.rivet.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {
    private val token = "ghp_sentinel123456"

    @Test
    fun aLiveToolResultIsMaskedBeforeItReachesTheTranscriptOrNextRequest() = runBlocking {
        val requests = mutableListOf<List<AgentMessage>>()
        var calls = 0
        val loop = AgentLoop(
            requestModel = { messages, _, _ ->
                requests += messages
                if (++calls == 1) AgentResponse(toolCalls = listOf(
                    AgentToolCall("read", "read_file", """{"path":"notes.txt"}""")))
                else AgentResponse(text = "Done")
            },
            prepareTool = { call ->
                PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
                    AgentToolResult(call.id, call.name, "the token is $token")
                }
            },
            requestApproval = { true },
            secretValues = listOf(token),
        )
        val result = loop.run(listOf(AgentMessage.user("Read the notes")), emptyList())
        val stored = result.messages.flatMap { it.toolResults }.single().content
        assertFalse(stored.contains(token))
        assertTrue(stored.contains(SecretRedactor.MASK))
        assertFalse(requests.last().any { message ->
            message.text.contains(token) ||
                message.toolResults.any { it.content.contains(token) }
        })
    }
}
