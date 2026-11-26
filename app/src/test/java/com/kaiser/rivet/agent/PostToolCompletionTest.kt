package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.ProviderError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PostToolCompletionTest {
    @Test fun blankAfterAReceiptRetriesTheSameStep() = runTest {
        val outcome = complete("", "Here is the file summary.")

        assertEquals(AgentStopReason.Completed, outcome.result.stopReason)
        assertEquals("Here is the file summary.", outcome.result.messages.last().text)
        assertEquals(3, outcome.requests.size)
        assertEquals(outcome.requests[1], outcome.requests[2])
        assertEquals(AgentRole.Tool, outcome.requests[2].last().role)
        assertEquals(1, outcome.toolExecutions)
    }

    @Test fun sixBlankCompletionsReportAnEmptyResponseAndKeepTheToolReceipt() = runTest {
        val outcome = complete("", " ", "", "", "", "")

        assertEquals("EmptyResponse", outcome.result.stopReason.name)
        assertEquals(7, outcome.requests.size)
        assertEquals(1, outcome.toolExecutions)
        val receipt = outcome.result.messages.last()
        assertEquals(AgentRole.Tool, receipt.role)
        assertEquals("read-1", receipt.toolResults.single().callId)
    }

    @Test fun aBlankBeforeAnyToolAlsoUsesTheRetryPolicy() = runTest {
        var requests = 0
        val result = AgentLoop(
            requestModel = { _, _, _ -> requests++; AgentResponse(text = " ") },
            prepareTool = { error("No tool requested") },
            requestApproval = { error("No approval required") },
        ).run(listOf(AgentMessage.user("Summarize this")), emptyList())

        assertEquals("EmptyResponse", result.stopReason.name)
        assertEquals(6, requests)
        assertEquals(AgentRole.User, result.messages.last().role)
    }

    @Test fun transientFailuresRetryBeforeToolDispatchAndHonorRetryAfter() = runTest {
        val start = testScheduler.currentTime
        val times = mutableListOf<Long>()
        var attempts = 0
        val result = AgentLoop(
            requestModel = { _, _, _ ->
                times += testScheduler.currentTime - start
                when (attempts++) {
                    0 -> throw ProviderError.RateLimited(retryAfterMs = 2_000)
                    1 -> throw ProviderError.Timeout("progress")
                    2 -> throw ProviderError.Network("connect")
                    3 -> throw ProviderError.Server(503)
                    else -> AgentResponse(text = "Recovered")
                }
            },
            prepareTool = { error("No tool requested") },
            requestApproval = { error("No approval required") },
        ).run(listOf(AgentMessage.user("Summarize this")), emptyList())

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(2_000L, times[1])
        assertEquals(5, times.size)
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
