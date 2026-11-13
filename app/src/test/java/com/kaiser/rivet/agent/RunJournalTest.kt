package com.kaiser.rivet.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunJournalTest {
    @get:Rule val temp = TemporaryFolder()

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
        var run = RunMachine.begin("r2", "ws", "Long job", now = 1)
        run = RunMachine.start(run, now = 2)
        run = RunMachine.step(run, "step one", now = 3)
        run = RunMachine.step(run, "step two", now = 4)
        journal.save(run)

        // A new process: recoverLatest never pretends this completed.
        val recovered = RunJournal(DirectoryRunStorage(temp.root)).recoverLatest()!!
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
        assertNotNull(journal.latest())
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
