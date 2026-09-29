package com.kaiser.rivet.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentLoop
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolEffect
import com.kaiser.rivet.agent.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProcessesTest {
    @Test fun directStopCancelsOnlyTheSelectedForegroundCommand() {
        val processes = ManagedProcesses()
        val first = Job()
        val second = Job()
        val one = processes.registerCommand("pwd", "", "tree-one", "Project One", first)!!
        val two = processes.registerCommand("ls", "src", "tree-two", "Project Two", second)!!

        assertTrue(processes.ownsWorkspace(one, "tree-one"))
        assertFalse(processes.ownsWorkspace(one, "tree-two"))
        assertNull(processes.stopDescription(one, "tree-two"))
        assertTrue(processes.activeForWorkspace("tree-two").isEmpty())
        assertTrue(processes.stop(one))
        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        assertFalse(processes.stop("stale-id"))
        assertFalse(processes.stop(one))
        processes.failCommand(one)
        assertEquals(ManagedProcessStatus.Stopped, processes.processes.value.single { it.id == one }.status)
        assertEquals(ManagedProcessStatus.Running, processes.processes.value.single { it.id == two }.status)
        assertTrue(ManagedProcesses().processes.value.isEmpty())
    }

    @Test fun processCapAndCompletedHistoryAreBounded() {
        val processes = ManagedProcesses()
        val previews = (1..ManagedProcesses.MAX_PERSISTENT_PROCESSES).map { index ->
            processes.registerPreview("tree", "Project", "index.html", "http://127.0.0.1:$index") {}
        }
        assertTrue(previews.all { it != null })
        assertNull(processes.registerPreview("tree", "Project", "index.html", "http://127.0.0.1:99") {})
        assertFalse(processes.canStartPersistent())
        val stopped = previews.first()!!
        assertTrue(processes.stop(stopped))
        processes.finishPreview(stopped)
        assertTrue(processes.canStartPersistent())
        assertEquals(ManagedProcessStatus.Stopped,
            processes.processes.value.single { it.id == stopped }.status)

        repeat(20) { index ->
            val job = Job()
            val id = processes.registerCommand("pwd", "", "tree", "Project", job)!!
            processes.finishCommand(id, RuntimeCommandResult(0, cwd = "", sync = "no_changes"))
        }
        assertTrue(processes.processes.value.size <= 8 + ManagedProcesses.MAX_PERSISTENT_PROCESSES)
    }

    @Test fun outputAndDisplayMetadataAreBounded() {
        val processes = ManagedProcesses()
        val id = processes.registerCommand(
            "curl -u alice:secret https://user:pass@example.test/file token=secret",
            "", "tree", "Project", Job())!!
        val output = "x".repeat(20_000)
        processes.finishCommand(id, RuntimeCommandResult(0, output, cwd = "", sync = "no_changes"))
        val item = processes.processes.value.single()
        assertTrue(item.label.contains("[redacted]"))
        assertFalse(item.label.contains("alice:secret"))
        assertFalse(item.label.contains("user:pass"))
        assertTrue(item.output.length <= ManagedProcesses.MAX_OUTPUT_CHARS)
        assertEquals(ManagedProcessStatus.Completed, item.status)
    }

    @Test fun stopAllStopsOnlyRegisteredOperations() {
        val processes = ManagedProcesses()
        val command = Job()
        val commandId = processes.registerCommand("pwd", "", "tree", "Project", command)!!
        var previewStops = 0
        val previewId = processes.registerPreview("tree", "Project", "index.html",
            "http://127.0.0.1:42000/") { previewStops++ }!!
        processes.stopAll()
        assertTrue(command.isCancelled)
        assertEquals(1, previewStops)
        assertEquals(ManagedProcessStatus.Stopping,
            processes.processes.value.single { it.id == commandId }.status)
        assertEquals(ManagedProcessStatus.Stopped,
            processes.processes.value.single { it.id == previewId }.status)
    }

    @Test fun stoppingActiveCommandCancelsItsAgentTurnWithoutSuccessResult() = runTest {
        val processes = ManagedProcesses()
        val call = AgentToolCall("cmd", "run_command", """{"command":"sleep 60"}""")
        val idReady = kotlinx.coroutines.CompletableDeferred<String>()
        val results = mutableListOf<AgentToolResult>()
        var modelRequests = 0
        val running = async {
            AgentLoop(
                requestModel = { _, _, _ -> modelRequests++; AgentResponse(toolCalls = listOf(call)) },
                prepareTool = { requested -> preparedCommand(requested) {
                    val id = processes.registerCommand("sleep 60", "", "tree", "Project",
                        currentCoroutineContext()[Job]!!)!!
                    idReady.complete(id)
                    try { awaitCancellation() }
                    finally { processes.failCommand(id) }
                } },
                requestApproval = { true },
                beforeMutation = { null },
            ).run(listOf(AgentMessage.user("Run command")), emptyList(), onMessage = { message ->
                results += message.toolResults
            })
        }
        yield()
        val processId = idReady.await()
        assertTrue(processes.stop(processId))
        try { running.await(); throw AssertionError("stopped agent turn returned normally") }
        catch (_: CancellationException) { }
        assertEquals(1, modelRequests)
        assertEquals(1, results.size)
        assertTrue(results.single().content.contains("interrupted"))
        assertTrue(results.none { !it.error })
    }

    private fun preparedCommand(call: AgentToolCall, action: suspend () -> Unit) =
        com.kaiser.rivet.agent.PreparedAgentTool(call,
            AgentApprovalRequest(call, "Run", "sleep 60"),
            effect = AgentToolEffect.ForegroundCommand) {
            action()
            AgentToolResult(call.id, call.name, "{}")
        }
}
