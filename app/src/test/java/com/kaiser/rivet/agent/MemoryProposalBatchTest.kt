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
        val proposals = linkedMapOf<String, AgentToolResult>()
        val appended = mutableListOf<AgentMessage>()
        var responses = 0
        val loop = AgentLoop(
            requestModel = { history, _, _ ->
                history.flatMap { it.toolResults }.forEach { proposals[it.callId] = it }
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

        val result = loop.run(listOf(AgentMessage.user("Remember these facts")), emptyList(),
            onMessage = { appended += it })

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(1, cards.size)
        assertTrue(facts.all(cards.single().detail::contains))
        assertEquals(if (approved) listOf(facts) else emptyList<List<String>>(), writes)
        assertEquals(if (approved) 1 else 0, result.mutationsCompleted)
        val timeline = AgentActivityProjection.conversation(appended)
        val notices = timeline.filterIsInstance<AgentConversationItem.SystemNotice>()
        if (approved) {
            assertTrue(notices.isEmpty())
        } else {
            assertEquals(AgentConversationItem.SystemNotice("Memory not updated — proposal declined"), timeline.last())
            assertEquals("I proposed both facts.", (timeline[timeline.lastIndex - 1] as AgentConversationItem.Message).message.text)
            assertTrue(modelMessages(appended).none { it.text == "Memory not updated — proposal declined" })
        }
        assertEquals(facts.size, proposals.size)
        assertTrue(proposals.values.all {
            it.content.contains("The user decides whether to save it.") &&
                it.content.contains("Nothing is saved if the user declines.")
        })
    }
}
