package com.kaiser.rivet.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun receiptSavedBeforeTheToolBatchSurvivesReopening() = runBlocking {
        val journal = RunJournal(DirectoryRunStorage(temp.root))
        var run = RunMachine.start(RunMachine.begin("r", "ws", "Write", sessionId = "chat"))
        val write = AgentToolCall("first", "write_file", """{"path":"marker","content":"effect"}""")
        val wait = AgentToolCall("second", "run_command", """{"command":"wait"}""")
        val file = File(temp.root, "marker")
        try {
            AgentLoop(
                requestModel = { _, _, _ -> AgentResponse(toolCalls = listOf(write, wait)) },
                prepareTool = { call ->
                    PreparedAgentTool(call, AgentApprovalRequest(call, "Run tool", call.name)) {
                        if (call == wait) throw kotlinx.coroutines.CancellationException("process disappeared")
                        file.appendText("effect")
                        AgentToolResult(call.id, call.name, "{}")
                    }
                },
                requestApproval = { true },
                journalTool = { key, receipt ->
                    run = run.copy(toolReceipts = run.toolReceipts + (key to receipt))
                    journal.save(run)
                },
            ).run(listOf(AgentMessage.user("Write a marker")), emptyList())
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("effect", file.readText())

        val reopened = RunJournal(DirectoryRunStorage(temp.root)).recoverLatest("chat")!!
        var requests = 0
        val resumed = AgentLoop(
            requestModel = { _, _, _ ->
                if (requests++ == 0) AgentResponse(toolCalls = listOf(write, wait))
                else AgentResponse(text = "Finished")
            },
            prepareTool = { error("A previously attempted operation was prepared again") },
            requestApproval = { true },
            resumeReceipts = reopened.toolReceipts,
        ).run(listOf(AgentMessage.user("Write a marker")), emptyList())
        val results = resumed.messages.flatMap { it.toolResults }
        assertEquals("Already done · write_file", results[0].summary)
        assertEquals("Outcome unknown · run_command", results[1].summary)
    }

    @Test fun stateMachineOnlyMovesForwardLegally() {
        var run = RunMachine.begin("r1", "ws", "Fix tests", now = 1)
        assertEquals(RunPhase.Queued, run.phase)
        run = RunMachine.start(run, now = 2)
        run = RunMachine.step(run, "read the failing test", now = 3)
        run = RunMachine.step(run, "edited the fixture", now = 4)
        assertEquals(2, run.lastCompletedStep)
        run = RunMachine.pause(run, RunPauseReason.Budget, now = 5)
        assertEquals("budget reached — raise or stop", run.plainState())
        run = RunMachine.resume(run, now = 6)
        run = RunMachine.finish(run, RunPhase.Done, now = 7)
        assertEquals(RunPhase.Done, run.phase)
        assertTrue(run.steps.map { it.index } == listOf(1, 2))
    }

    @Test fun processDeathRecoversTheLastCompletedStepAndResumeContext() = runBlocking {
        val storage = DirectoryRunStorage(temp.root)
        val journal = RunJournal(storage)
        var run = RunMachine.begin("r2", "ws", "Long job", now = 1, sessionId = "chat")
        run = RunMachine.start(run, now = 2)
        run = RunMachine.step(run, "step one", now = 3)
        run = RunMachine.step(run, "step two", now = 4)
        journal.save(run)

        // A new process: recoverLatest never pretends this completed.
        val recovered = RunJournal(DirectoryRunStorage(temp.root)).recoverLatest("chat")!!
        assertEquals(RunPhase.Paused, recovered.phase)
        assertEquals(RunPauseReason.Interrupted, recovered.pause)
        assertEquals(2, recovered.lastCompletedStep)
        assertTrue(RunContext.content(recovered)!!.contains("step two"))
        assertTrue(RunContext.content(recovered)!!.contains("interrupted"))

        val resumed = RunMachine.resume(recovered, now = 5)
        val next = RunMachine.step(resumed, "step three", now = 6)
        assertEquals(3, next.lastCompletedStep)
        assertEquals(2, journal.load("r2")!!.lastCompletedStep) // old snapshot until saved
        journal.save(next)
        assertNotNull(journal.latest("chat"))
    }

    @Test fun anotherChatCannotRecoverOrInjectTheNewestProjectRun() = runBlocking {
        val journal = RunJournal(DirectoryRunStorage(temp.root))
        var original = RunMachine.start(RunMachine.begin("run-a", "ws", "Original",
            now = 1, sessionId = "chat-a"), now = 2)
        repeat(12) { original = RunMachine.step(original, "step ${it + 1}", now = 3L + it) }
        journal.save(original)
        val second = RunMachine.begin("run-b", "ws", "Second", now = 30, sessionId = "chat-b")
        journal.save(second)

        val newChat = journal.recoverLatest("chat-c")
        assertNull(newChat)
        assertNull(newChat?.recoveryMessage())
        assertFalse(newChat?.resumable == true)
        assertTrue(RunContext.updates(emptyList(), "ws", original.ownedBy("chat-c")).isEmpty())
        assertEquals(RunPhase.Running, journal.load(original.id)?.phase)
        assertEquals(second.id, journal.recoverLatest("chat-b")?.id)

        val resumed = requireNotNull(journal.recoverLatest("chat-a"))
        assertEquals(original.id, resumed.id)
        assertEquals(RunPauseReason.Interrupted, resumed.pause)
        assertTrue(resumed.recoveryMessage().orEmpty().contains("12 completed step"))
        assertTrue(RunContext.updates(emptyList(), "ws", resumed.ownedBy("chat-a")).isNotEmpty())
    }

    @Test fun recoveryCanFindTheInterruptedTurnThatOwnsTheTranscriptTail() = runBlocking {
        val journal = RunJournal(DirectoryRunStorage(temp.root))
        val live = RunMachine.start(RunMachine.begin("paused", "ws", "Continue the audit",
            now = 1, sessionId = "chat"), now = 2)
        journal.save(RunMachine.pause(live, RunPauseReason.User, now = 3))
        val later = RunMachine.start(RunMachine.begin("other", "ws", "Different turn",
            now = 4, sessionId = "chat"), now = 5)
        journal.save(RunMachine.finish(later, RunPhase.Failed, now = 6))

        assertEquals(listOf("other", "paused"), journal.records("chat").map { it.id })
        assertEquals("paused", journal.recoverLatest("chat", "Continue the audit")?.id)
        assertEquals(RunPhase.Failed, journal.recoverLatest("chat", "Different turn")?.phase)
    }

    @Test fun legacyRunWithoutSessionIdStaysHidden() = runBlocking {
        val storage = DirectoryRunStorage(temp.root)
        storage.write("${RunJournal.ROOT}/legacy/run.json",
            """{"id":"legacy","workspaceId":"ws","title":"Original","phase":"Running"}""")
        val journal = RunJournal(storage)

        assertNotNull(journal.load("legacy"))
        assertNull(journal.recoverLatest("new-chat"))
        assertNull(journal.recoverLatest(null))
    }

    @Test fun systemTimeoutAndNetworkLossPauseWithoutLosingTheRun() {
        var run = RunMachine.start(RunMachine.begin("r4", "ws", "Cloud job", now = 1))
        val timedOut = RunMachine.timeout(run, now = 2)
        assertEquals(RunPauseReason.SystemTimeout, timedOut.pause)
        assertEquals("paused by the system — tap to continue", timedOut.plainState())
        run = RunMachine.networkLost(RunMachine.resume(timedOut, now = 3), now = 4)
        assertEquals(RunPauseReason.NetworkLost, run.pause)
        assertEquals(RunPhase.Running, RunMachine.networkBack(run, now = 5).phase)
        assertFalse(RunMachine.recover(timedOut).interrupted)
    }

    @Test fun terminalRunsAreNotReportedAsInterrupted() {
        val finished = RunMachine.finish(RunMachine.start(RunMachine.begin("r3", null, "x")), RunPhase.Done)
        assertFalse(RunMachine.recover(finished).interrupted)
        assertEquals(null, RunContext.content(finished))
    }

    @Test fun rejectsPathTraversalInRunIdAndWritesAtomicallyWithoutLeavingTempFiles() = runBlocking {
        val root = temp.newFolder("atomic-runs")
        val storage = DirectoryRunStorage(root)
        val journal = RunJournal(storage)

        val valid = RunMachine.start(RunMachine.begin("r-safe-1", "ws", "Safe run", now = 1))
        journal.save(valid)
        val loaded = journal.load("r-safe-1")
        assertNotNull(loaded)
        assertFalse(File(root, "${RunJournal.ROOT}/r-safe-1/.run.json.tmp").exists())

        var threw = false
        try {
            journal.save(RunMachine.begin("../../escape", "ws", "Bad run", now = 2))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(File(root.parentFile, "escape/run.json").exists())
    }
}
