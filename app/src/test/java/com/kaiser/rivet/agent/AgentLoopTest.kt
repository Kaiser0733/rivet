package com.kaiser.rivet.agent

import com.kaiser.rivet.storage.AgentSessionCodec
import com.kaiser.rivet.provider.ProviderError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    @Test fun unavailableCommandStopsBeforeApprovalExecutionOrFalseSuccess() = runTest {
        val call = AgentToolCall("run-1", "run_command", """{"command":"printf ok"}""")
        var modelRequests = 0
        var approvals = 0
        var executions = 0
        val result = AgentLoop(
            requestModel = { _, _, _ ->
                modelRequests++
                if (modelRequests == 1) AgentResponse(toolCalls = listOf(call))
                else AgentResponse(text = "The command completed successfully")
            },
            prepareTool = { PreparedAgentTool(call, AgentApprovalRequest(call, "Run", "printf ok")) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { approvals++; true },
            mutationBlocker = { "workspace_unavailable" },
        ).run(listOf(AgentMessage.user("Run command")), emptyList())

        assertEquals(1, modelRequests)
        assertEquals(0, approvals)
        assertEquals(0, executions)
        assertFalse(result.messages.any { it.text.contains("completed successfully") })
        assertTrue(result.messages.flatMap { it.toolResults }.single().content.contains("workspace_unavailable"))
    }

    @Test fun workspaceLostAfterApprovalStopsBeforeCommandExecution() = runTest {
        val call = AgentToolCall("run-1", "run_command", """{"command":"printf ok"}""")
        var requests = 0
        var approvals = 0
        var executions = 0
        val result = AgentLoop(
            requestModel = { _, _, _ ->
                requests++
                if (requests == 1) AgentResponse(toolCalls = listOf(call))
                else AgentResponse(text = "The command succeeded")
            },
            prepareTool = { PreparedAgentTool(call, AgentApprovalRequest(call, "Run", "printf ok")) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { approvals++; true },
            beforeMutation = { "workspace_unavailable" },
        ).run(listOf(AgentMessage.user("Run command")), emptyList())

        assertEquals(AgentStopReason.RuntimeBlocked, result.stopReason)
        assertEquals("workspace_unavailable", result.failureCode)
        assertEquals(1, approvals)
        assertEquals(0, executions)
        assertEquals(1, requests)
        assertTrue(result.messages.flatMap { it.toolResults }.single().content.contains("workspace_unavailable"))
    }
    @Test fun providerResourceExhaustionIsSurfacedWithoutRetry() = runTest {
        var requests = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> requests++; throw ProviderError.ResourceExhausted() },
            prepareTool = { error("No tools") },
            requestApproval = { error("No approvals") },
        )
        try {
            loop.run(listOf(AgentMessage.user("Continue")), emptyList())
            error("Expected provider failure")
        } catch (_: ProviderError.ResourceExhausted) {
            assertEquals(1, requests)
        }
    }

    @Test fun unsafeRuntimeEntryStopsBeforeAnotherModelTurn() = runTest {
        val call = AgentToolCall("read-1", "read_file", """{"path":"unsafe"}""")
        var requests = 0
        val result = AgentLoop(
            requestModel = { _, _, _ ->
                requests++
                if (requests == 1) AgentResponse(toolCalls = listOf(call))
                else AgentResponse(text = "I read the file successfully")
            },
            prepareTool = { requested -> PreparedAgentTool(requested, null) {
                AgentToolResult(requested.id, requested.name, AgentToolError.content("unsafe_entry"), error = true)
            } },
            requestApproval = { error("Read-only operation has no approval") },
        ).run(listOf(AgentMessage.user("Read it")), emptyList())

        assertEquals(AgentStopReason.RuntimeBlocked, result.stopReason)
        assertEquals("unsafe_entry", result.failureCode)
        assertEquals(1, requests)
        assertFalse(result.messages.any { it.text.contains("read the file successfully") })
    }

    @Test fun repeatedSyncBlockStopsWithoutSecondApprovalOrCommand() = runTest {
        val first = AgentToolCall("one", "run_command", """{"command":"pwd"}""")
        val second = first.copy(id = "two")
        var modelRequests = 0
        var approvals = 0
        var checkpoints = 0
        var executions = 0
        val result = AgentLoop(
            requestModel = { _, _, _ ->
                modelRequests++
                AgentResponse(toolCalls = listOf(if (modelRequests == 1) first else second))
            },
            prepareTool = { call -> PreparedAgentTool(call, AgentApprovalRequest(call, "Run", "pwd")) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { approvals++; true },
            mutationBlocker = { "sync_required" },
            beforeMutation = { checkpoints++; null },
            failureState = { "sync_required" },
        ).run(listOf(AgentMessage.user("Run pwd")), emptyList())
        val results = result.messages.flatMap { it.toolResults }
        assertEquals(AgentStopReason.RuntimeBlocked, result.stopReason)
        assertEquals("sync_required", result.failureCode)
        assertEquals(1, modelRequests)
        assertEquals(0, approvals)
        assertEquals(0, checkpoints)
        assertEquals(0, executions)
        assertEquals(listOf("one"), results.map { it.callId })
        assertTrue(results[0].content.contains("safely reconcile"))
    }

    @Test fun changedBlockerStateAllowsCommandInLaterTurnAndRepeatedSuccessfulReads() = runTest {
        val calls = listOf(
            AgentToolCall("blocked", "run_command", """{"command":"pwd"}"""),
            AgentToolCall("resumed", "run_command", """{"command":"pwd"}"""),
            AgentToolCall("read1", "read_file", """{"path":"Main.kt"}"""),
            AgentToolCall("read2", "read_file", """{"path":"Main.kt"}"""),
        )
        var state = "sync_required"
        var executions = 0
        fun loop(responses: ArrayDeque<AgentResponse>) = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { call -> PreparedAgentTool(call,
                if (call.name == "run_command") AgentApprovalRequest(call, "Run", "pwd") else null) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { true },
            mutationBlocker = { if (state == "sync_required") "sync_required" else null },
            failureState = { state },
        )
        val blocked = loop(ArrayDeque(listOf(AgentResponse(toolCalls = listOf(calls[0])))))
            .run(listOf(AgentMessage.user("Continue")), emptyList())
        assertEquals(AgentStopReason.RuntimeBlocked, blocked.stopReason)
        state = "ready"
        val result = loop(ArrayDeque(calls.drop(1).map { AgentResponse(toolCalls = listOf(it)) } +
            AgentResponse(text = "done"))).run(listOf(AgentMessage.user("Continue")), emptyList())
        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(3, executions)
        assertEquals(0, result.messages.flatMap { it.toolResults }.count { it.error })
    }

    @Test fun contextOverflowCompactsAndRetriesOnlyTheModelRequest() = runTest {
        val call = AgentToolCall("edit", "write_file", "{}")
        var requests = 0
        var mutations = 0
        val seen = mutableListOf<List<AgentMessage>>()
        val result = AgentLoop(
            requestModel = { messages, _, _ ->
                requests++
                seen += messages
                when (requests) {
                    1 -> AgentResponse(toolCalls = listOf(call))
                    2 -> throw ProviderError.ContextOverflow()
                    else -> AgentResponse(text = "done")
                }
            },
            prepareTool = { PreparedAgentTool(call, AgentApprovalRequest(call, "Edit", "file")) {
                mutations++
                AgentToolResult(call.id, call.name, "x".repeat(3_000))
            } },
            requestApproval = { true },
            compactContext = { messages, force -> if (force) messages.take(1) else messages },
        ).run(listOf(AgentMessage.user("edit")), emptyList())

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(3, requests)
        assertEquals(1, mutations)
        assertEquals(listOf(AgentMessage.user("edit")), seen.last())
    }

    @Test fun compactionReplacesOnlyActiveContextBeforeNextModelRequest() = runTest {
        val old = listOf(AgentMessage.user("older"), AgentMessage.assistant("done"), AgentMessage.user("current"))
        val seen = mutableListOf<List<AgentMessage>>()
        val persisted = mutableListOf<AgentMessage>()
        val result = AgentLoop(
            requestModel = { messages, _, _ -> seen += messages; AgentResponse(text = "next") },
            prepareTool = { error("No tools") },
            requestApproval = { error("No approvals") },
            compactContext = { messages, _ -> messages.takeLast(1) },
        ).run(old, emptyList(), onMessage = { persisted += it })

        assertEquals(listOf(AgentMessage.user("current")), seen.single())
        assertEquals(listOf(AgentMessage.assistant("next")), persisted)
        assertEquals(listOf(AgentMessage.user("current"), AgentMessage.assistant("next")), result.messages)
    }

    @Test fun compactionFailureStopsWithoutExecutingTool() = runTest {
        var requests = 0
        val result = AgentLoop(
            requestModel = { _, _, _ -> requests++; AgentResponse() },
            prepareTool = { error("No tools") },
            requestApproval = { error("No approvals") },
            compactContext = { _, _ -> throw IllegalStateException("summary failed") },
        ).run(listOf(AgentMessage.user("continue")), emptyList())
        assertEquals(AgentStopReason.ContextUnavailable, result.stopReason)
        assertEquals(0, requests)
    }

    @Test
    fun checkpointFailureStopsApprovedMutationBeforeExecution() = runTest {
        val call = AgentToolCall("edit", "write_file", "{}")
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = listOf(call)) },
            prepareTool = { PreparedAgentTool(call, AgentApprovalRequest(call, "Edit", "file")) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { true },
            beforeMutation = { "checkpoint_unavailable" },
        )

        val result = loop.run(listOf(AgentMessage.user("edit")), emptyList())
        assertEquals(AgentStopReason.CheckpointUnavailable, result.stopReason)
        assertEquals(0, executions)
        val correlated = result.messages.last().toolResults.single()
        assertEquals("edit", correlated.callId)
        assertTrue(correlated.error)
        assertTrue("checkpoint_unavailable" in correlated.content)
    }

    @Test
    fun deniedCommandNeverStartsCheckpointOrExecution() = runTest {
        val call = AgentToolCall("command", "run_command", "{}")
        var checkpoints = 0
        var executions = 0
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(call)), AgentResponse(text = "Stopped")))
        val result = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { PreparedAgentTool(call, AgentApprovalRequest(call, "Run", "command")) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { false },
            beforeMutation = { checkpoints++; null },
        ).run(listOf(AgentMessage.user("run")), emptyList())

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(0, checkpoints)
        assertEquals(0, executions)
    }
    @Test
    fun createdPathStaysOrdinaryThroughRenameAndMoveButExistingDeleteIsDangerous() = runTest {
        val workspace = AgentToolExecutorTest.FakeWorkspace()
        val executor = AgentToolExecutor(workspace)
        val calls = listOf(
            AgentToolCall("create", "create_file", """{"path":"scratch.ts"}"""),
            AgentToolCall("rename", "rename_path", """{"path":"scratch.ts","new_name":"renamed.ts"}"""),
            AgentToolCall("move", "move_path", """{"path":"renamed.ts","destination":"tmp"}"""),
            AgentToolCall("delete-created", "delete_path", """{"path":"tmp/renamed.ts"}"""),
            AgentToolCall("delete-existing", "delete_path", """{"path":"Max.txt"}"""),
        )
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = calls), AgentResponse(text = "Done")))
        val approvals = mutableListOf<AgentApprovalRequest>()
        val result = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = executor::prepare,
            requestApproval = { approvals += it; it.call.id != "delete-existing" },
            describeDestructive = executor::describeDestructive,
        ).run(listOf(AgentMessage.user("Test")), AgentToolExecutor.definitions)

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertFalse(approvals.first { it.call.id == "delete-created" }.dangerous)
        val existing = approvals.first { it.call.id == "delete-existing" }
        assertTrue(existing.dangerous)
        assertTrue(existing.title.contains("existing file"))
        assertTrue(existing.detail.contains("80,106 bytes"))
        assertTrue(existing.detail.contains("Deletion is permanent"))
        assertEquals(1, workspace.deletes)
        assertTrue(result.messages.flatMap { it.toolResults }.last().error)
    }

    @Test
    fun deletingAgentCreatedDirectoryUsesStrongerApproval() = runTest {
        val workspace = AgentToolExecutorTest.FakeWorkspace()
        val executor = AgentToolExecutor(workspace)
        val calls = listOf(
            AgentToolCall("mkdir", "create_directory", """{"path":".rivet-test"}"""),
            AgentToolCall("rmdir", "delete_path", """{"path":".rivet-test"}"""),
        )
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = calls), AgentResponse(text = "Stopped")))
        val approvals = mutableListOf<AgentApprovalRequest>()

        val result = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = executor::prepare,
            requestApproval = { approvals += it; it.call.id != "rmdir" },
            describeDestructive = executor::describeDestructive,
        ).run(listOf(AgentMessage.user("Clean up test files")), AgentToolExecutor.definitions)

        assertEquals(AgentStopReason.Completed, result.stopReason)
        val removal = approvals.single { it.call.id == "rmdir" }
        assertTrue(removal.dangerous)
        assertTrue(removal.title.contains("folder"))
        assertTrue(removal.detail.contains("files added outside Rivet"))
        assertEquals(0, workspace.deletes)
    }

    @Test
    fun movedExistingPathRemainsDangerousAndGateBlocksExecutionUntilResolved() = runTest {
        val workspace = AgentToolExecutorTest.FakeWorkspace()
        val executor = AgentToolExecutor(workspace)
        val gate = AgentApprovalGate()
        val move = AgentToolCall("move-existing", "move_path", """{"path":"Max.txt","destination":"tmp"}""")
        val delete = AgentToolCall("delete-existing", "delete_path", """{"path":"tmp/Max.txt"}""")
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(move, delete)), AgentResponse(text = "Done")))
        val approvals = mutableListOf<AgentApprovalRequest>()
        val running = async {
            AgentLoop(
                requestModel = { _, _, _ -> responses.removeFirst() },
                prepareTool = executor::prepare,
                requestApproval = { approvals += it; gate.await(it) },
                describeDestructive = executor::describeDestructive,
            ).run(listOf(AgentMessage.user("Move")), AgentToolExecutor.definitions)
        }
        yield()
        assertEquals("move-existing", gate.pending.value?.call?.id)
        assertEquals(0, workspace.deletes)
        assertTrue(gate.resolve(gate.pending.value!!.approvalToken, true))
        yield()
        assertEquals("delete-existing", gate.pending.value?.call?.id)
        assertTrue(approvals.last().dangerous)
        assertEquals(0, workspace.deletes)
        val deleteToken = gate.pending.value!!.approvalToken
        assertTrue(gate.resolve(deleteToken, false))
        assertFalse(gate.resolve(deleteToken, true))
        assertEquals(0, workspace.deletes)
        assertEquals(AgentStopReason.Completed, running.await().stopReason)
    }
    @Test
    fun textResponseCompletesTurn() = runTest {
        val requests = mutableListOf<List<AgentMessage>>()
        val loop = AgentLoop(
            requestModel = { messages, _, onText ->
                requests += messages
                onText("Finished")
                AgentResponse(text = "Finished")
            },
            prepareTool = { error("No tool should be prepared") },
            requestApproval = { error("No approval should be requested") },
        )
        val initial = listOf(AgentMessage.user("Inspect the project"))

        val result = loop.run(initial, emptyList())

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(1, result.modelIterations)
        assertEquals(initial, requests.single())
        assertEquals(
            initial + AgentMessage.assistant("Finished"),
            result.messages,
        )
    }

    @Test
    fun readResultReturnsToSameModelBeforeFinalResponse() = runTest {
        val call = AgentToolCall("call-1", "read_file", """{"path":"src/Main.kt"}""")
        val responses = ArrayDeque(
            listOf(
                AgentResponse(toolCalls = listOf(call)),
                AgentResponse(text = "The entry point is small."),
            ),
        )
        val requests = mutableListOf<List<AgentMessage>>()
        var executions = 0
        val loop = AgentLoop(
            requestModel = { messages, _, _ ->
                requests += messages
                responses.removeFirst()
            },
            prepareTool = { requested ->
                assertEquals(call, requested)
                PreparedAgentTool(requested, approval = null) {
                    executions++
                    AgentToolResult(
                        callId = requested.id,
                        name = requested.name,
                        content = """{"path":"src/Main.kt","text":"fun main() = Unit"}""",
                        summary = "Read  src/Main.kt",
                    )
                }
            },
            requestApproval = { error("Read-only tools do not need approval") },
        )

        val result = loop.run(
            listOf(AgentMessage.user("Inspect the entry point")),
            emptyList(),
        )

        assertEquals(1, executions)
        assertEquals(2, requests.size)
        assertEquals(call, requests[1][1].toolCalls.single())
        assertEquals("call-1", requests[1][2].toolResults.single().callId)
        assertEquals("The entry point is small.", result.messages.last().text)
        assertEquals(2, result.modelIterations)
        assertEquals(1, result.toolCalls)
        assertEquals(AgentStopReason.Completed, result.stopReason)
    }

    @Test
    fun identicalDeniedMutationIsNotPromptedAgain() = runTest {
        val call = AgentToolCall(
            "first",
            "delete_path",
            """{"path":"src/Old.kt"}""",
        )
        val repeated = call.copy(id = "second")
        val responses = ArrayDeque(
            listOf(
                AgentResponse(toolCalls = listOf(call)),
                AgentResponse(toolCalls = listOf(repeated)),
                AgentResponse(text = "I left the file unchanged."),
            ),
        )
        var approvals = 0
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { requested ->
                PreparedAgentTool(
                    requested,
                    AgentApprovalRequest(requested, "Delete", "src/Old.kt"),
                ) {
                    executions++
                    AgentToolResult(requested.id, requested.name, "{}")
                }
            },
            requestApproval = {
                approvals++
                false
            },
        )

        val result = loop.run(listOf(AgentMessage.user("Remove the old file")), emptyList())

        assertEquals(1, approvals)
        assertEquals(0, executions)
        assertEquals(2, result.messages.flatMap { it.toolResults }.count { it.error })
        assertEquals(AgentStopReason.NoProgress, result.stopReason)
        assertTrue(result.messages.flatMap { it.toolResults }.last().content.contains("no_progress"))
    }

    @Test
    fun cancellationKeepsCompletedTextButNoIncompleteToolCall() = runTest {
        val completed = mutableListOf<AgentMessage>()
        val loop = AgentLoop(
            requestModel = { _, _, onText ->
                onText("Partial answer")
                awaitCancellation()
            },
            prepareTool = { error("No complete tool call exists") },
            requestApproval = { error("No approval should be requested") },
        )
        val running = async {
            loop.run(
                listOf(AgentMessage.user("Inspect")),
                emptyList(),
                onMessage = { completed += it },
            )
        }
        yield()

        running.cancelAndJoin()

        assertEquals(listOf(AgentMessage.assistant("Partial answer")), completed)
    }

    @Test
    fun workspaceReplacementRejectsEveryUnstartedCall() = runTest {
        val calls = listOf(
            AgentToolCall("one", "read_file", """{"path":"A.kt"}"""),
            AgentToolCall("two", "delete_path", """{"path":"B.kt"}"""),
        )
        var preparations = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = calls) },
            prepareTool = {
                preparations++
                error("A changed workspace must not prepare a tool")
            },
            requestApproval = { error("No approval should survive replacement") },
            workspaceIsCurrent = { false },
        )

        val result = loop.run(listOf(AgentMessage.user("Inspect")), emptyList())

        assertEquals(0, preparations)
        assertEquals(AgentStopReason.WorkspaceChanged, result.stopReason)
        val rejected = result.messages.last().toolResults
        assertEquals(listOf("one", "two"), rejected.map { it.callId })
        assertTrue(rejected.all { it.error && "workspace_changed" in it.content })
    }

    @Test
    fun approvedMutationExecutesOnceThenContinues() = runTest {
        val call = AgentToolCall("edit-1", "write_file", "{}")
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(call)), AgentResponse(text = "Done.")))
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { requested -> PreparedAgentTool(
                requested,
                AgentApprovalRequest(requested, "Edit", "src/Main.kt"),
            ) {
                executions++
                AgentToolResult(requested.id, requested.name, "{\"ok\":true}")
            } },
            requestApproval = { true },
        )

        val result = loop.run(listOf(AgentMessage.user("Edit it")), emptyList())

        assertEquals(1, executions)
        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals("edit-1", result.messages[2].toolResults.single().callId)
    }

    @Test
    fun multipleCallsKeepProviderOrderAndCorrelation() = runTest {
        val calls = listOf(
            AgentToolCall("a", "read_file", "{}"),
            AgentToolCall("b", "search_files", "{}"),
        )
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = calls), AgentResponse(text = "Done")))
        val executed = mutableListOf<String>()
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { call -> PreparedAgentTool(call, null) {
                executed += call.id
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { error("No approval") },
        )

        val result = loop.run(listOf(AgentMessage.user("Inspect")), emptyList())

        assertEquals(listOf("a", "b"), executed)
        assertEquals(listOf("a", "b"), result.messages[2].toolResults.map { it.callId })
    }

    @Test
    fun largeReadsDoNotStarveLaterSmallToolsOrApprovedMutation() = runTest {
        val calls = (1..10).map { AgentToolCall("read-$it", "read_file", """{"path":"A.kt"}""") } + listOf(
            AgentToolCall("list", "list_directory", "{}"),
            AgentToolCall("search", "search_files", """{"query":"content"}"""),
            AgentToolCall("edit", "write_file", """{"path":"A.kt","content":"new","expected_sha256":"${"a".repeat(64)}"}"""),
        )
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = calls), AgentResponse(text = "Finished")))
        val executed = mutableListOf<String>()
        val workspace = AgentToolExecutorTest.FakeWorkspace(text = "文".repeat(30_000))
        val executor = AgentToolExecutor(workspace)
        var approvals = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { call ->
                val prepared = executor.prepare(call)
                PreparedAgentTool(call, prepared.approval, prepared.resultContentLimitBytes) {
                    executed += call.id
                    prepared.execute()
                }
            },
            requestApproval = { approvals++; true },
            canPersistToolOutput = { candidate, reserve -> AgentSessionCodec.fits(candidate, reserve) },
        )

        val result = loop.run(listOf(AgentMessage.user("Inspect and edit")), emptyList())
        val toolResults = result.messages.flatMap { it.toolResults }
        assertTrue(toolResults.sumOf { it.content.toByteArray(Charsets.UTF_8).size } > 64 * 1024)
        assertEquals(calls.map { it.id }, executed)
        assertEquals(executed, toolResults.map { it.callId })
        assertTrue(toolResults.none { it.error })
        assertEquals(1, approvals)
        assertEquals(1, workspace.writes)
        assertEquals(AgentStopReason.Completed, result.stopReason)
        AgentSessionCodec.encode(result.messages)
    }

    @Test
    fun sixtyCallsAcrossThirtyIterationsCompleteNormally() = runTest {
        var requests = 0
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ ->
                if (requests++ < 30) AgentResponse(toolCalls = (1..2).map {
                    AgentToolCall("$requests-$it", "list_directory", "{}")
                }) else AgentResponse(text = "Finished")
            },
            prepareTool = { call -> PreparedAgentTool(call, null) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { error("No approval") },
            canPersistToolOutput = { candidate, reserve -> AgentSessionCodec.fits(candidate, reserve) },
        )
        val result = loop.run(listOf(AgentMessage.user("Inspect")), emptyList())
        assertEquals(60, executions)
        assertEquals(31, result.modelIterations)
        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertTrue(result.messages.flatMap { it.toolResults }.none { it.error })
    }

    @Test
    fun oversizedIndividualResultBecomesCorrelatedStructuredError() = runTest {
        val call = AgentToolCall("giant", "search_files", "{}")
        val responses = ArrayDeque(listOf(
            AgentResponse(toolCalls = listOf(call)),
            AgentResponse(toolCalls = listOf(AgentToolCall("small", "list_directory", "{}"))),
            AgentResponse(text = "Recovered"),
        ))
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { requested -> PreparedAgentTool(requested, null) {
                AgentToolResult(requested.id, requested.name, if (requested.id == "giant") "x".repeat(100_000) else "{}")
            } },
            requestApproval = { error("No approval") },
        )

        val result = loop.run(listOf(AgentMessage.user("Search")), emptyList())
        val limited = result.messages.flatMap { it.toolResults }.first()

        assertEquals("giant", limited.callId)
        assertTrue(limited.error)
        assertEquals(AgentLoop.OUTPUT_LIMIT_CONTENT, limited.content)
        assertTrue(limited.content.toByteArray(Charsets.UTF_8).size <= 24 * 1024)
        assertEquals("Recovered", result.messages.last().text)
        assertEquals("{}", result.messages.flatMap { it.toolResults }.last().content)
    }

    @Test
    fun mutationReserveFailureKeepsDurableHistoryAndRejectsRemainingCalls() = runTest {
        val calls = listOf(AgentToolCall("edit", "write_file", "{}"), AgentToolCall("next", "read_file", "{}"))
        val initial = listOf(AgentMessage.user("x".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES - 2000)))
        var durable = initial
        var approvals = 0
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = calls) },
            prepareTool = { call -> PreparedAgentTool(call, AgentApprovalRequest(call, "Edit", "A.kt"),
                resultContentLimitBytes = 1024,
            ) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { approvals++; true },
            canPersistToolOutput = { candidate, reserve -> AgentSessionCodec.fits(candidate, reserve) },
        )
        val result = loop.run(initial, emptyList(), onMessage = { message ->
            val candidate = durable + message
            AgentSessionCodec.encode(candidate)
            durable = candidate
        })
        assertEquals(AgentStopReason.SessionLimit, result.stopReason)
        assertEquals(0, approvals)
        assertEquals(0, executions)
        assertEquals(initial.single(), durable.first())
        assertEquals(result.messages, durable)
        assertEquals(calls.map { it.id }, durable.last().toolResults.map { it.callId })
        assertTrue(durable.last().toolResults.all { it.error && "session_limit" in it.content })
    }

    @Test
    fun smallMutationFitsNearSessionCapWithoutMaximumReadReserve() = runTest {
        val call = AgentToolCall("create", "create_file", """{"path":"A.kt"}""")
        val initial = listOf(AgentMessage.user("x".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES - 8000)))
        val executor = AgentToolExecutor(AgentToolExecutorTest.FakeWorkspace())
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(call)), AgentResponse(text = "Finished")))
        var durable = initial
        var approvals = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = executor::prepare,
            requestApproval = { approvals++; true },
            canPersistToolOutput = { candidate, reserve -> AgentSessionCodec.fits(candidate, reserve) },
        )
        val result = loop.run(initial, emptyList(), onMessage = { message ->
            val candidate = durable + message
            AgentSessionCodec.encode(candidate)
            durable = candidate
        })
        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(1, approvals)
        assertTrue(durable.flatMap { it.toolResults }.none { it.error })
        assertEquals(result.messages, durable)
    }

    @Test
    fun readOnlySessionExhaustionPreservesEarlierMutationResult() = runTest {
        val calls = listOf(AgentToolCall("edit", "write_file", "{}"), AgentToolCall("read", "read_file", "{}"))
        val initial = listOf(AgentMessage.user("x".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES - 8000)))
        var durable = initial
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = calls) },
            prepareTool = { call -> PreparedAgentTool(call,
                if (call.id == "edit") AgentApprovalRequest(call, "Edit", "A.kt") else null,
                resultContentLimitBytes = if (call.id == "edit") 1024 else AgentLoop.MAX_TOOL_RESULT_BYTES,
            ) {
                AgentToolResult(call.id, call.name,
                    if (call.id == "edit") "{\"sha256\":\"${"a".repeat(64)}\"}" else "x".repeat(20_000))
            } },
            requestApproval = { true },
            canPersistToolOutput = { candidate, reserve -> AgentSessionCodec.fits(candidate, reserve) },
        )
        val result = loop.run(initial, emptyList(), onMessage = { message ->
            val candidate = durable + message
            AgentSessionCodec.encode(candidate)
            durable = candidate
        })
        assertEquals(AgentStopReason.SessionLimit, result.stopReason)
        val results = durable.last().toolResults
        assertEquals("edit", results[0].callId)
        assertTrue(!results[0].error && "sha256" in results[0].content)
        assertTrue(results[1].error && "session_limit" in results[1].content)
        assertEquals(result.messages, durable)
    }

    @Test
    fun sessionReserveFailureStopsBeforePersistingCallOrPreparingMutation() = runTest {
        val call = AgentToolCall("edit", "write_file", "{}")
        var prepared = 0
        var approvals = 0
        val initial = listOf(AgentMessage.user("Edit"))
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = listOf(call)) },
            prepareTool = {
                prepared++
                PreparedAgentTool(it, AgentApprovalRequest(it, "Edit", "A.kt")) {
                    error("Mutation must not execute")
                }
            },
            requestApproval = { approvals++; true },
            canPersistToolOutput = { _, _ -> false },
        )

        val result = loop.run(initial, emptyList())

        assertEquals(AgentStopReason.SessionLimit, result.stopReason)
        assertEquals(initial, result.messages)
        assertEquals(0, prepared)
        assertEquals(0, approvals)
    }

    @Test
    fun executionFailureBecomesCorrelatedToolError() = runTest {
        val call = AgentToolCall("bad", "read_file", "{}")
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(call)), AgentResponse(text = "Recovered")))
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { requested -> PreparedAgentTool(requested, null) { error("boom") } },
            requestApproval = { error("No approval") },
        )

        val result = loop.run(listOf(AgentMessage.user("Inspect")), emptyList())

        val failure = result.messages[2].toolResults.single()
        assertEquals("bad", failure.callId)
        assertTrue(failure.error)
        assertTrue("tool_failed" in failure.content)
        assertEquals(AgentStopReason.Completed, result.stopReason)
    }

    @Test
    fun workspaceChangeWhileAwaitingApprovalPreventsMutation() = runTest {
        val call = AgentToolCall("edit", "write_file", "{}")
        var current = true
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = listOf(call)) },
            prepareTool = { requested -> PreparedAgentTool(
                requested,
                AgentApprovalRequest(requested, "Edit", "A.kt"),
            ) {
                executions++
                AgentToolResult(requested.id, requested.name, "{}")
            } },
            requestApproval = { current = false; true },
            workspaceIsCurrent = { current },
        )

        val result = loop.run(listOf(AgentMessage.user("Edit")), emptyList())

        assertEquals(0, executions)
        assertEquals(AgentStopReason.WorkspaceChanged, result.stopReason)
        assertTrue("workspace_changed" in result.messages.last().toolResults.single().content)
    }

    @Test
    fun cancellationWhileWaitingForApprovalStartsNoMutation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val completed = mutableListOf<AgentMessage>()
        var executions = 0
        val calls = listOf(
            AgentToolCall("edit", "delete_path", "{}"),
            AgentToolCall("next", "write_file", "{}"),
        )
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = calls) },
            prepareTool = { requested -> PreparedAgentTool(
                requested,
                AgentApprovalRequest(requested, "Delete", "A.kt"),
            ) {
                executions++
                AgentToolResult(requested.id, requested.name, "{}")
            } },
            requestApproval = { entered.complete(Unit); awaitCancellation() },
        )
        val running = async {
            loop.run(
                listOf(AgentMessage.user("Delete")),
                emptyList(),
                onMessage = { completed += it },
            )
        }
        entered.await()

        running.cancelAndJoin()

        assertEquals(0, executions)
        val results = completed.flatMap { it.toolResults }
        assertEquals(listOf("edit", "next"), results.map { it.callId })
        assertTrue(results.all { it.error && "cancelled" in it.content })
    }

    @Test
    fun stopStillCancelsAfterMoreThanFiftyOperations() = runTest {
        val entered = CompletableDeferred<Unit>()
        val completed = mutableListOf<AgentMessage>()
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = listOf(
                AgentToolCall("read-${executions + 1}", "read_file", "{}"),
            )) },
            prepareTool = { call -> PreparedAgentTool(call, null) {
                if (++executions == 60) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { error("No approval") },
        )
        val running = async {
            loop.run(listOf(AgentMessage.user("Inspect")), emptyList(), onMessage = { completed += it })
        }
        entered.await()
        running.cancelAndJoin()
        assertEquals(60, executions)
        assertEquals(59, completed.flatMap { it.toolResults }.count { !it.error })
        assertEquals("read-60", completed.last().toolResults.single().callId)
        assertTrue("cancelled" in completed.last().toolResults.single().content)
    }

    @Test
    fun emergencyIterationWatchdogStopsPathologicalLoop() = runTest {
        var requests = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ ->
                requests++
                AgentResponse(toolCalls = listOf(AgentToolCall("c-$requests", "read_file", "{}")))
            },
            prepareTool = { call -> PreparedAgentTool(call, null) {
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { error("No approval") },
        )

        val result = loop.run(listOf(AgentMessage.user("Loop")), emptyList())

        assertEquals(AgentLoop.RUNAWAY_MODEL_ITERATIONS, requests)
        assertEquals(AgentStopReason.RunawayGuard, result.stopReason)
        assertEquals(AgentLoop.RUNAWAY_MODEL_ITERATIONS, result.toolCalls)
    }

    @Test
    fun oversizedToolBatchStopsWithoutExecutingAndKeepsCorrelation() = runTest {
        val calls = (1..AgentLoop.RUNAWAY_TOOL_CALLS + 1).map { AgentToolCall("c-$it", "read_file", "{}") }
        var executions = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = calls) },
            prepareTool = { call -> PreparedAgentTool(call, null) {
                executions++
                AgentToolResult(call.id, call.name, "{}")
            } },
            requestApproval = { error("No approval") },
        )

        val result = loop.run(listOf(AgentMessage.user("Loop")), emptyList())

        assertEquals(0, executions)
        assertEquals(AgentStopReason.RunawayGuard, result.stopReason)
        assertEquals(calls.map { it.id }, result.messages.last().toolResults.map { it.callId })
    }

    @Test
    fun conflictResultCanBeFollowedByReadAndFinalResponse() = runTest {
        val patch = AgentToolCall("patch", "apply_patch", "{}")
        val read = AgentToolCall("read", "read_file", "{}")
        val responses = ArrayDeque(listOf(
            AgentResponse(toolCalls = listOf(patch)),
            AgentResponse(toolCalls = listOf(read)),
            AgentResponse(text = "Recovered"),
        ))
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { call -> PreparedAgentTool(
                call,
                if (call.name == "apply_patch") AgentApprovalRequest(call, "Edit", "A.kt") else null,
            ) {
                if (call.name == "apply_patch") AgentToolResult(
                    call.id, call.name, "{\"error\":\"conflict\"}", error = true,
                ) else AgentToolResult(call.id, call.name, "{\"text\":\"new\"}")
            } },
            requestApproval = { true },
        )

        val result = loop.run(listOf(AgentMessage.user("Patch")), emptyList())

        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertTrue(result.messages.flatMap { it.toolResults }.first().error)
        assertEquals("Recovered", result.messages.last().text)
    }

    @Test
    fun cancellationAfterMutationCommitPersistsItsResult() = runTest {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<AgentMessage>()
        val call = AgentToolCall("edit", "write_file", "{}")
        val loop = AgentLoop(
            requestModel = { _, _, _ -> AgentResponse(toolCalls = listOf(call)) },
            prepareTool = { requested -> PreparedAgentTool(
                requested,
                AgentApprovalRequest(requested, "Edit", "A.kt"),
            ) {
                withContext(NonCancellable) {
                    committed.complete(Unit)
                    release.await()
                    AgentToolResult(requested.id, requested.name, "{\"ok\":true}")
                }
            } },
            requestApproval = { true },
        )
        val running = async {
            loop.run(
                listOf(AgentMessage.user("Edit")),
                emptyList(),
                onMessage = { completed += it },
            )
        }
        committed.await()

        running.cancel()
        release.complete(Unit)
        running.cancelAndJoin()

        val result = completed.flatMap { it.toolResults }.single()
        assertEquals("edit", result.callId)
        assertTrue(!result.error && "ok" in result.content)
    }
}
