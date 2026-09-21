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

        assertTrue(gate.resolve("call-1", approved = true))
        assertFalse(gate.resolve("call-1", approved = true))
        assertTrue(waiting.await())
    }

    @Test
    fun cancelledWaitRemovesExecutableApproval() = runTest {
        val call = AgentToolCall("call-1", "delete_path", "{}")
        val gate = AgentApprovalGate()
        val waiting = async { gate.await(AgentApprovalRequest(call, "Delete", "A.kt")) }
        yield()

        waiting.cancelAndJoin()

        assertFalse(gate.resolve("call-1", approved = true))
        assertTrue(gate.pending.value == null)
    }
}
