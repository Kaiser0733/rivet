package com.kaiser.rivet.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalGateTest {
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
}
