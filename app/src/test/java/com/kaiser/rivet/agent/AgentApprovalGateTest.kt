package com.kaiser.rivet.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalGateTest {
    @Test
    fun expiredApprovalReachesTheModelAndCannotExecute() = runTest {
        val gate = AgentApprovalGate()
        val call = AgentToolCall("shell", "run_command", """{"command":"write"}""")
        var requests = 0
        var executed = false
        val waiting = async {
            AgentLoop(
                requestModel = { messages, _, _ ->
                    if (requests++ == 0) AgentResponse(toolCalls = listOf(call)) else {
                        assertTrue(messages.last().toolResults.single().content.contains("approval_expired"))
                        AgentResponse(text = "The approval expired.")
                    }
                },
                prepareTool = { PreparedAgentTool(it, AgentApprovalRequest(it, "Run command", "write")) {
                    executed = true
                    AgentToolResult(it.id, it.name, "{}")
                } },
                requestApproval = gate::await,
            ).run(listOf(AgentMessage.user("Run the command")), emptyList())
        }
        yield()
        val token = gate.pending.value!!.approvalToken

        try {
            val result = withTimeoutOrNull(121_000) { waiting.await() }
            assertEquals(AgentStopReason.Completed, result?.stopReason)
            assertEquals("Approval expired · run_command",
                result?.messages?.flatMap { it.toolResults }?.single()?.summary)
            assertNull(gate.pending.value)
            assertFalse(gate.resolve(token, approved = true))
            assertFalse(executed)
        } finally {
            waiting.cancelAndJoin()
        }
    }

    @Test
    fun approvalChecksLiveDetailsBeforeGranting() = runTest {
        val gate = AgentApprovalGate()
        val call = AgentToolCall("delete", "delete_path", "{}")
        var sameTarget = true
        val pending = async {
            runCatching {
                gate.await(AgentApprovalRequest(call, "Delete file", "A.kt", reviewAtGrant = { sameTarget }))
            }
        }
        yield()
        val token = gate.pending.value!!.approvalToken
        sameTarget = false
        assertFalse(gate.grant(token))
        assertEquals("approval_changed", (pending.await().exceptionOrNull() as ApprovalUnavailable).code)
    }

    @Test
    fun approvalCanBeResolvedOnlyOnce() = runTest {
        val call = AgentToolCall("call-1", "write_file", "{}")
        val gate = AgentApprovalGate()
        val waiting = async {
            gate.await(AgentApprovalRequest(call, "Edit", "src/Main.kt"))
        }
        yield()

        val token = gate.pending.value!!.approvalToken
        assertTrue(gate.resolve(token, approved = true))
        assertFalse(gate.resolve(token, approved = true))
        assertTrue(waiting.await())
    }

    @Test
    fun reusedProviderCallIdCannotResolveALaterApproval() = runTest {
        val gate = AgentApprovalGate()
        val request = AgentApprovalRequest(
            AgentToolCall("same-id", "write_file", "{}"), "Edit", "A.kt",
        )
        val first = async { gate.await(request) }
        yield()
        val firstToken = gate.pending.value!!.approvalToken
        assertTrue(firstToken != 0L)
        assertTrue(gate.resolve(firstToken, approved = true))
        assertTrue(first.await())

        val second = async { gate.await(request) }
        yield()
        val secondToken = gate.pending.value!!.approvalToken

        assertTrue(secondToken != firstToken)
        assertFalse(gate.resolve(firstToken, approved = true))
        assertTrue(gate.pending.value != null)
        assertTrue(gate.resolve(secondToken, approved = false))
        assertFalse(second.await())
    }

    @Test
    fun cancelledWaitRemovesExecutableApproval() = runTest {
        val call = AgentToolCall("call-1", "delete_path", "{}")
        val gate = AgentApprovalGate()
        val waiting = async { gate.await(AgentApprovalRequest(call, "Delete", "A.kt")) }
        yield()
        val token = gate.pending.value!!.approvalToken

        waiting.cancelAndJoin()

        assertFalse(gate.resolve(token, approved = true))
        assertTrue(gate.pending.value == null)
    }

    @Test
    fun pendingApprovalHoldsAcrossALongBackgroundWait() = runTest {
        val gate = AgentApprovalGate()
        val waiting = async {
            gate.await(AgentApprovalRequest(
                AgentToolCall("call-bg", "create_file", "{}"), "Create file", "notes.txt",
            ))
        }
        yield()
        val token = gate.pending.value!!.approvalToken

        // Backgrounding the app must not turn the wait into an implicit denial.
        kotlinx.coroutines.delay(60_000)
        assertTrue(gate.pending.value != null)
        assertFalse(waiting.isCompleted)

        assertTrue(gate.resolve(token, approved = true))
        assertTrue(waiting.await())
    }

    @Test
    fun concurrentApprovalsResolveInFifoOrder() = runTest {
        val gate = AgentApprovalGate()
        val first = async {
            gate.await(
                AgentApprovalRequest(AgentToolCall("call-1", "write_file", "{}"), "Edit", "A.kt", requester = "Subagent · a"),
                owner = "worker-1",
            )
        }
        yield()
        val second = async {
            gate.await(
                AgentApprovalRequest(AgentToolCall("call-2", "write_file", "{}"), "Edit", "B.kt", requester = "Subagent · b"),
                owner = "worker-2",
            )
        }
        yield()

        val head = gate.pending.value!!
        assertEquals("call-1", head.call.id)
        assertEquals("Subagent · a", head.requester)
        assertTrue(gate.resolve(head.approvalToken, approved = true))
        assertTrue(first.await())

        val promoted = gate.pending.value!!
        assertEquals("call-2", promoted.call.id)
        assertEquals("Subagent · b", promoted.requester)
        assertFalse(gate.resolve(head.approvalToken, approved = false))
        assertTrue(gate.resolve(promoted.approvalToken, approved = false))
        assertFalse(second.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun cancelOwnerDropsOnlyThatOwnersPendingApproval() = runTest {
        val gate = AgentApprovalGate()
        val first = async {
            gate.await(
                AgentApprovalRequest(AgentToolCall("call-1", "write_file", "{}"), "Edit", "A.kt"),
                owner = "worker-1",
            )
        }
        yield()
        val second = async {
            gate.await(
                AgentApprovalRequest(AgentToolCall("call-2", "write_file", "{}"), "Edit", "B.kt"),
                owner = "worker-2",
            )
        }
        yield()

        gate.cancel("worker-1")
        first.join()
        assertTrue(first.isCancelled)

        val promoted = gate.pending.value!!
        assertEquals("call-2", promoted.call.id)
        assertTrue(gate.resolve(promoted.approvalToken, approved = true))
        assertTrue(second.await())
        assertNull(gate.pending.value)
    }
}
