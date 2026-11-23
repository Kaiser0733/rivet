package com.kaiser.rivet.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProposalBatchTest {
    private val facts = listOf("Codeword: papaya-17", "Number: 7777")

    @Test fun twoSequentialProposalsGetOneApprovalAndOneWrite() = runTest {
        assertBatch(approved = true)
    }

    @Test fun decliningTheBatchWritesNothing() = runTest {
        assertBatch(approved = false)
    }

    private suspend fun assertBatch(approved: Boolean) {
        val cards = mutableListOf<AgentApprovalRequest>()
        val writes = mutableListOf<List<String>>()
        var responses = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ ->
                val next = responses++
                if (next < facts.size) AgentResponse(toolCalls = listOf(
                    AgentToolCall("remember-$next", "remember", """{"fact":"${facts[next]}"}"""),
                )) else AgentResponse(text = "I proposed both facts.")
            },
            prepareTool = { call ->
                val fact = facts[call.id.substringAfterLast('-').toInt()]
                PreparedAgentTool(call, AgentApprovalRequest(call, "Update project memory", fact),
                    effect = AgentToolEffect.WorkspaceMutation, memoryFact = fact) {
                    writes += listOf(fact)
                    AgentToolResult(call.id, call.name, "{}")
                }
            },
            requestApproval = { request -> cards += request; approved },
            writeMemoryBatch = { call, batch ->
                writes += batch
                AgentToolResult(call.id, call.name, "{}")
            },
        )

        val result = loop.run(listOf(AgentMessage.user("Remember these facts")), emptyList())

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(1, cards.size)
        assertTrue(facts.all(cards.single().detail::contains))
        assertEquals(if (approved) listOf(facts) else emptyList<List<String>>(), writes)
        assertEquals(if (approved) 1 else 0, result.mutationsCompleted)
    }
}
