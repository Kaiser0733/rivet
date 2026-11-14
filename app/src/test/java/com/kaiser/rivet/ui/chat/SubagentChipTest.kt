package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.subagent.WorkerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentChipTest {
    private fun worker(state: RunPhase = RunPhase.Running) = WorkerRecord(
        id = "w1",
        runId = "run",
        parentSessionId = "s",
        workspaceId = "tree",
        job = "Review the parser",
        brief = "Check nullability",
        configId = "c1",
        model = "model-1",
        state = state,
    )

    @Test fun zeroWorkersRendersTheEmptySpacerInsteadOfAChipLabel() {
        assertNull(subagentChipText(0))
        assertEquals("1 subagent ⌄", subagentChipText(1))
        assertEquals("3 subagents ⌄", subagentChipText(3))
    }

    @Test fun popoverStateAndActivityFollowTheWorkerRecord() {
        assertEquals("running", workerStateLabel(worker()))
        assertEquals("waiting", workerStateLabel(worker(RunPhase.Queued)))
        assertEquals("done", workerStateLabel(worker(RunPhase.Done)))
        assertEquals("failed", workerStateLabel(worker(RunPhase.Failed)))
        assertEquals("stopped", workerStateLabel(worker(RunPhase.Stopped)))
        assertEquals("Looking through the project…",
            workerActivityLine(worker().copy(activity = "Looking through the project…")))
        assertEquals("waiting for a slot", workerActivityLine(worker(RunPhase.Queued)))
    }
}
