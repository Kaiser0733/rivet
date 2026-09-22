package com.kaiser.rivet.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
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
        assertEquals(AgentStopReason.Completed, result.stopReason)
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
    fun repeatedToolResultsStayWithinTurnOutputBudgetAndLoopRecovers() = runTest {
        val calls = (1..4).map { AgentToolCall("read-$it", "read_file", "{}") }
        val responses = ArrayDeque(listOf(
            AgentResponse(toolCalls = calls),
            AgentResponse(text = "I reached the output limit and can continue selectively."),
        ))
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { call -> PreparedAgentTool(call, null) {
                AgentToolResult(call.id, call.name, "x".repeat(20_000))
            } },
            requestApproval = { error("No approval") },
        )

        val result = loop.run(listOf(AgentMessage.user("Read repeatedly")), emptyList())
        val toolResults = result.messages.flatMap { it.toolResults }

        assertTrue(toolResults.sumOf { it.content.toByteArray(Charsets.UTF_8).size } <= 64 * 1024)
        assertEquals(calls.map { it.id }, toolResults.map { it.callId })
        assertTrue(toolResults.last().error)
        assertTrue("output_limit" in toolResults.last().content)
        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals("I reached the output limit and can continue selectively.", result.messages.last().text)
    }

    @Test
    fun oversizedIndividualResultBecomesCorrelatedStructuredError() = runTest {
        val call = AgentToolCall("giant", "search_files", "{}")
        val responses = ArrayDeque(listOf(
            AgentResponse(toolCalls = listOf(call)),
            AgentResponse(text = "Recovered"),
        ))
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { requested -> PreparedAgentTool(requested, null) {
                AgentToolResult(requested.id, requested.name, "x".repeat(100_000))
            } },
            requestApproval = { error("No approval") },
        )

        val result = loop.run(listOf(AgentMessage.user("Search")), emptyList())
        val limited = result.messages.flatMap { it.toolResults }.single()

        assertEquals("giant", limited.callId)
        assertTrue(limited.error)
        assertEquals("{\"error\":\"output_limit\"}", limited.content)
        assertTrue(limited.content.toByteArray(Charsets.UTF_8).size <= 24 * 1024)
        assertEquals("Recovered", result.messages.last().text)
    }

    @Test
    fun exhaustedOutputBudgetDoesNotRequestOrExecuteMutation() = runTest {
        val reads = (1..3).map { AgentToolCall("read-$it", "read_file", "{}") }
        val mutation = AgentToolCall("edit", "write_file", "{}")
        val responses = ArrayDeque(listOf(
            AgentResponse(toolCalls = reads + mutation),
            AgentResponse(text = "Stopped before editing."),
        ))
        var approvals = 0
        var mutations = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = { call ->
                if (call == mutation) PreparedAgentTool(
                    call,
                    AgentApprovalRequest(call, "Edit", "A.kt"),
                ) {
                    mutations++
                    AgentToolResult(call.id, call.name, "{\"ok\":true}")
                } else PreparedAgentTool(call, null) {
                    AgentToolResult(call.id, call.name, "x".repeat(20_000))
                }
            },
            requestApproval = { approvals++; true },
        )

        val result = loop.run(listOf(AgentMessage.user("Read then edit")), emptyList())
        val mutationResult = result.messages.flatMap { it.toolResults }.last()

        assertEquals(0, approvals)
        assertEquals(0, mutations)
        assertEquals("edit", mutationResult.callId)
        assertEquals("{\"error\":\"output_limit\"}", mutationResult.content)
        assertEquals("Stopped before editing.", result.messages.last().text)
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
    fun iterationLimitStopsAfterTwentyModelRequests() = runTest {
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

        assertEquals(AgentLoop.MAX_MODEL_ITERATIONS, requests)
        assertEquals(AgentStopReason.IterationLimit, result.stopReason)
        assertEquals(20, result.toolCalls)
    }

    @Test
    fun oversizedToolBatchStopsWithoutExecutingAndKeepsCorrelation() = runTest {
        val calls = (1..51).map { AgentToolCall("c-$it", "read_file", "{}") }
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
        assertEquals(AgentStopReason.ToolCallLimit, result.stopReason)
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

        assertEquals("edit", completed.flatMap { it.toolResults }.single().callId)
    }
}
