package com.kaiser.rivet.runtime

import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentLoop
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentStopReason
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AgentToolExecutorTest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CommandToolTest {
    private val call = AgentToolCall("command-1", "run_command",
        """{"command":"echo hello","cwd":"src","timeout_ms":30000}""")

    @Test fun commandWaitsForOneShotApprovalAndDenialExecutesNothing() = runTest {
        val gate = AgentApprovalGate()
        var executions = 0
        val executor = AgentToolExecutor(AgentToolExecutorTest.FakeWorkspace(), runCommand = { _, _, _ ->
            executions++
            RuntimeCommandResult(0, "hello\n", cwd = "src", sync = "no_changes")
        })
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(call)), AgentResponse(text = "done")))
        val running = async {
            AgentLoop(
                requestModel = { _, _, _ -> responses.removeFirst() },
                prepareTool = executor::prepare,
                requestApproval = gate::await,
            ).run(listOf(AgentMessage.user("Run it")), AgentToolExecutor.definitions)
        }
        yield()
        assertEquals("command-1", gate.pending.value?.call?.id)
        assertEquals(0, executions)
        val approvalToken = gate.pending.value!!.approvalToken
        assertTrue(gate.resolve(approvalToken, false))
        assertFalse(gate.resolve(approvalToken, true))
        val result = running.await()
        assertEquals(0, executions)
        assertEquals(AgentStopReason.Completed, result.stopReason)
        val denied = result.messages.flatMap { it.toolResults }.single()
        assertEquals(call.id, denied.callId)
        assertEquals(call.name, denied.name)
        assertTrue(denied.content.contains("denied"))
    }

    @Test fun approvedCommandReportsExitOutputAndSyncSeparately() = runTest {
        val gate = AgentApprovalGate()
        var executions = 0
        val executor = AgentToolExecutor(AgentToolExecutorTest.FakeWorkspace(), runCommand = { command, cwd, timeout ->
            assertEquals("echo hello", command)
            assertEquals("src", cwd)
            assertEquals(30_000L, timeout)
            executions++
            RuntimeCommandResult(7, "hello\n", "warning\n", cwd = cwd, sync = "conflict", syncPath = "src/file")
        })
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(call)), AgentResponse(text = "done")))
        var requests = 0
        val running = async {
            AgentLoop(
                requestModel = { _, _, _ -> requests++; responses.removeFirst() },
                prepareTool = executor::prepare,
                requestApproval = gate::await,
            ).run(listOf(AgentMessage.user("Run it")), AgentToolExecutor.definitions)
        }
        yield()
        assertEquals(0, executions)
        val approvalToken = gate.pending.value!!.approvalToken
        assertTrue(gate.resolve(approvalToken, true))
        assertFalse(gate.resolve(approvalToken, true))
        val run = running.await()
        val result = run.messages.flatMap { it.toolResults }.single()
        val value = Json.parseToJsonElement(result.content).jsonObject
        assertEquals(AgentStopReason.RuntimeBlocked, run.stopReason)
        assertEquals(1, requests)
        assertEquals(1, executions)
        assertEquals(call.id, result.callId)
        assertEquals("7", value["exit_code"]!!.jsonPrimitive.content)
        assertEquals("hello\n", value["stdout"]!!.jsonPrimitive.content)
        assertEquals("warning\n", value["stderr"]!!.jsonPrimitive.content)
        assertEquals("conflict", value["sync"]!!.jsonPrimitive.content)
        assertEquals("src/file", value["sync_path"]!!.jsonPrimitive.content)
    }

    @Test fun largeOrMalformedOutputKeepsExitStatusInsideResultBound() = runTest {
        val output = "\u0000\u001b".repeat(12_000) + "end"
        val executor = AgentToolExecutor(AgentToolExecutorTest.FakeWorkspace(), runCommand = { _, _, _ ->
            RuntimeCommandResult(3, output, "error", cwd = "src", sync = "failed")
        })
        val result = executor.prepare(call).execute()
        val value = Json.parseToJsonElement(result.content).jsonObject
        assertTrue(result.content.toByteArray(Charsets.UTF_8).size <= AgentLoop.MAX_TOOL_RESULT_BYTES)
        assertEquals("3", value["exit_code"]!!.jsonPrimitive.content)
        assertEquals("failed", value["sync"]!!.jsonPrimitive.content)
        assertEquals("true", value["stdout_truncated"]!!.jsonPrimitive.content)
        assertFalse(result.error)
    }

    @Test fun invalidCwdNeverRequestsApprovalOrRunsCommand() = runTest {
        var executions = 0
        val executor = AgentToolExecutor(AgentToolExecutorTest.FakeWorkspace(), runCommand = { _, _, _ ->
            executions++
            RuntimeCommandResult(0, sync = "ok")
        })
        val invalid = executor.prepare(call.copy(arguments = """{"command":"pwd","cwd":"../other"}"""))
        assertNull(invalid.approval)
        assertTrue(invalid.execute().error)
        assertEquals(0, executions)
    }

    @Test fun pendingSyncPreventsSafToolResultsAndMutations() = runTest {
        val workspace = AgentToolExecutorTest.FakeWorkspace()
        val executor = AgentToolExecutor(workspace, requireSafCurrent = {
            throw MirrorFailure("sync_required")
        })
        val read = executor.prepare(AgentToolCall("read", "read_file", """{"path":"Main.kt"}""")).execute()
        val write = executor.prepare(AgentToolCall("write", "write_file",
            """{"path":"Main.kt","content":"edit","expected_sha256":"${"a".repeat(64)}"}"""))
        val blockedWrite = write.execute()

        assertEquals("sync_required", Json.parseToJsonElement(read.content).jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals("false", Json.parseToJsonElement(read.content).jsonObject["retryable"]!!.jsonPrimitive.content)
        assertTrue(Json.parseToJsonElement(read.content).jsonObject["required_action"]!!.jsonPrimitive.content.contains("reconcile"))
        assertNotNull(write.approval)
        assertEquals("sync_required", Json.parseToJsonElement(blockedWrite.content).jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals(0, workspace.writes)
    }

    @Test fun blockedCommandExplainsRequiredActionWithoutInventingExitStatus() = runTest {
        val executor = AgentToolExecutor(AgentToolExecutorTest.FakeWorkspace(), runCommand = { _, cwd, _ ->
            RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "sync_required")
        })
        val result = executor.prepare(call).execute()
        val value = Json.parseToJsonElement(result.content).jsonObject
        assertTrue(result.error)
        assertEquals("sync_required", value["error"]!!.jsonPrimitive.content)
        assertEquals("false", value["retryable"]!!.jsonPrimitive.content)
        assertTrue(value["required_action"]!!.jsonPrimitive.content.contains("reconcile"))
        assertNull(value["exit_code"])
    }

    @Test fun unavailableAfterApprovalStopsWithoutAnotherModelAnswer() = runTest {
        val gate = AgentApprovalGate()
        var requests = 0
        var attempts = 0
        val executor = AgentToolExecutor(AgentToolExecutorTest.FakeWorkspace(), runCommand = { _, cwd, _ ->
            attempts++
            RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "workspace_unavailable")
        })
        val running = async {
            AgentLoop(
                requestModel = { _, _, _ ->
                    requests++
                    if (requests == 1) AgentResponse(toolCalls = listOf(call))
                    else AgentResponse(text = "The command succeeded")
                },
                prepareTool = executor::prepare,
                requestApproval = gate::await,
            ).run(listOf(AgentMessage.user("Run it")), AgentToolExecutor.definitions)
        }
        yield()
        assertEquals(call.id, gate.pending.value?.call?.id)
        assertTrue(gate.resolve(gate.pending.value!!.approvalToken, true))
        val result = running.await()
        assertEquals(AgentStopReason.RuntimeBlocked, result.stopReason)
        assertEquals("workspace_unavailable", result.failureCode)
        assertEquals(1, attempts)
        assertEquals(1, requests)
        assertFalse(result.messages.any { it.text.contains("command succeeded") })
    }

    @Test fun captureRetainsBoundedHeadAndTailOfBinaryOutput() {
        val capture = BoundedCapture(64)
        val bytes = byteArrayOf(0xFF.toByte()) + "start".toByteArray() + ByteArray(20_000) { 'x'.code.toByte() } + "end".toByteArray()
        capture.append(bytes, bytes.size)
        val text = capture.text()
        assertTrue(capture.truncated)
        assertTrue(text.length < 200)
        assertTrue(text.contains("start"))
        assertTrue(text.endsWith("end"))
        assertTrue(text.contains('\uFFFD'))
    }
}
