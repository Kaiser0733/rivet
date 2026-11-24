package com.kaiser.rivet.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostToolCompletionTest {
    @Test fun aBlankAfterAReceiptReasksAndCompletesWithTheAnswer() = runTest {
        val outcome = complete("", "Here is the file summary.")

        assertEquals(AgentStopReason.Completed, outcome.result.stopReason)
        assertEquals("Here is the file summary.", outcome.result.messages.last().text)
        assertEquals(3, outcome.requests.size)
        assertEquals(AgentRole.Tool, outcome.requests[2][outcome.requests[2].lastIndex - 1].role)
        assertTrue(outcome.requests[2].last().text.contains("Give the final answer"))
        assertFalse(outcome.result.messages.any { it.text.contains("Give the final answer") })
        assertEquals(1, outcome.toolExecutions)
    }

    @Test fun threeBlankCompletionsReportAnEmptyResponseAndKeepTheToolReceipt() = runTest {
        val outcome = complete("", " ", "")

        assertEquals("EmptyResponse", outcome.result.stopReason.name)
        assertEquals(4, outcome.requests.size)
        assertEquals(1, outcome.toolExecutions)
        val receipt = outcome.result.messages.last()
        assertEquals(AgentRole.Tool, receipt.role)
        assertEquals("read-1", receipt.toolResults.single().callId)
    }

    @Test fun aBlankBeforeAnyToolFailsWithoutReasking() = runTest {
        var requests = 0
        val result = AgentLoop(
            requestModel = { _, _, _ -> requests++; AgentResponse(text = " ") },
            prepareTool = { error("No tool requested") },
            requestApproval = { error("No approval required") },
        ).run(listOf(AgentMessage.user("Summarize this")), emptyList())

        assertEquals("EmptyResponse", result.stopReason.name)
        assertEquals(1, requests)
        assertEquals(AgentRole.User, result.messages.last().role)
    }

    private data class Outcome(
        val result: AgentRunResult,
        val requests: List<List<AgentMessage>>,
        val toolExecutions: Int,
    )

    private suspend fun complete(vararg answers: String): Outcome {
        val call = AgentToolCall("read-1", "read_file", """{"path":"notes.txt"}""")
        val requests = mutableListOf<List<AgentMessage>>()
        var toolExecutions = 0
        val loop = AgentLoop(
            requestModel = { messages, _, _ ->
                requests += messages
                if (requests.size == 1) AgentResponse(text = "I will read the file.", toolCalls = listOf(call))
                else AgentResponse(text = answers[requests.size - 2])
            },
            prepareTool = {
                PreparedAgentTool(it, approval = null) {
                    toolExecutions++
                    AgentToolResult(it.id, it.name, """{"text":"File contents"}""")
                }
            },
            requestApproval = { error("No approval required") },
        )
        val result = loop.run(listOf(AgentMessage.user("Summarize the notes")), emptyList())
        return Outcome(result, requests, toolExecutions)
    }
}
