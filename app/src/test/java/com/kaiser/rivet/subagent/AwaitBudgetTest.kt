package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.RunPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitBudgetTest {
    private fun worker(
        id: String,
        state: RunPhase = RunPhase.Running,
        waitingReason: String? = null,
    ) = WorkerRecord(
        id = id,
        runId = "run",
        parentSessionId = "session",
        workspaceId = "workspace",
        job = "job",
        brief = "brief",
        configId = "config",
        model = "model",
        state = state,
        waitingReason = waitingReason,
    )

    @Test fun approvalWaitDoesNotConsumeTheAwaitBudget() = runTest {
        val workers = MutableStateFlow(listOf(
            worker("w1", waitingReason = WorkerRecord.WAITING_FOR_APPROVAL),
        ))
        val result = async {
            awaitWatchedWorkers(workers, setOf("w1"), timeoutMs = 60_000L,
                now = { testScheduler.currentTime })
        }

        advanceTimeBy(120_000L)
        runCurrent()
        assertFalse(result.isCompleted)

        workers.value = listOf(worker("w1", state = RunPhase.Done))
        runCurrent()
        assertTrue(result.await())
    }

    @Test fun runningWorkerStillExhaustsTheAwaitBudget() = runTest {
        val workers = MutableStateFlow(listOf(worker("w1")))
        val result = async {
            awaitWatchedWorkers(workers, setOf("w1"), timeoutMs = 60_000L,
                now = { testScheduler.currentTime })
        }

        advanceTimeBy(60_001L)
        runCurrent()
        assertFalse(result.await())
    }
}
