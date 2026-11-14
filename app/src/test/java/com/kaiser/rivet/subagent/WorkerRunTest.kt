package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerRunTest {
    @Test fun waitingToRunningToDoneUsesRunMachine() {
        var worker = WorkerRun.queued("w1", "Review", "ws", "waiting for a slot", now = 1)
        assertEquals(WorkerDisplayState.Waiting, worker.state)
        worker = worker.start(now = 2)
        assertEquals(WorkerDisplayState.Running, worker.state)
        assertEquals(null, worker.waitingReason)
        worker = worker.finish(RunPhase.Done, now = 3)
        assertEquals(WorkerDisplayState.Done, worker.state)
        assertEquals(RunPhase.Done, worker.record.phase)
    }

    @Test fun processDeathRecoversARunningWorkerAsPausedInterrupted() {
        var worker = WorkerRun.queued("w1", "Review", "ws", "waiting", now = 1)
        worker = worker.start(now = 2)
        worker = worker.recover(now = 3)
        assertEquals(WorkerDisplayState.Paused, worker.state)
        assertEquals(RunPauseReason.Interrupted, worker.record.pause)
    }

    @Test fun networkPauseKeepsItsReason() {
        var worker = WorkerRun.queued("w1", "Review", "ws", "waiting", now = 1)
        worker = worker.start(now = 2)
        worker = worker.pause(RunPauseReason.NetworkLost, now = 3)
        assertEquals(WorkerDisplayState.Paused, worker.state)
        assertEquals(RunPauseReason.NetworkLost, worker.record.pause)
        assertEquals("network lost — resumes when connected", worker.waitingReason)
    }
}
