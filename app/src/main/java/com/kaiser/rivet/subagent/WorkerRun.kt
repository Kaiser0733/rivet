package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.RunMachine
import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.RunRecord

/** UI-facing worker states. The persisted machine remains RunRecord/RunMachine. */
enum class WorkerDisplayState {
    Waiting,
    Running,
    Paused,
    Done,
    Failed,
    Stopped,
}

/**
 * A worker record is a RunRecord plus only the waiting label P1 needs. State
 * transitions are delegated to RunMachine so workers cannot invent a lifecycle
 * separate from runs.
 */
data class WorkerRun(
    val record: RunRecord,
    val waitingReason: String? = null,
) {
    val state: WorkerDisplayState
        get() = when (record.phase) {
            RunPhase.Queued -> WorkerDisplayState.Waiting
            RunPhase.Running -> WorkerDisplayState.Running
            RunPhase.Paused -> WorkerDisplayState.Paused
            RunPhase.Done -> WorkerDisplayState.Done
            RunPhase.Failed -> WorkerDisplayState.Failed
            RunPhase.Stopped -> WorkerDisplayState.Stopped
        }

    fun start(now: Long = System.currentTimeMillis()): WorkerRun =
        copy(record = RunMachine.start(record, now), waitingReason = null)

    fun queue(reason: String): WorkerRun {
        require(record.phase == RunPhase.Queued) { "only a queued worker can wait" }
        return copy(waitingReason = reason)
    }

    fun pause(reason: RunPauseReason, now: Long = System.currentTimeMillis()): WorkerRun =
        copy(record = RunMachine.pause(record, reason, now), waitingReason = reason.plain)

    fun finish(
        phase: RunPhase,
        failure: String? = null,
        now: Long = System.currentTimeMillis(),
    ): WorkerRun = copy(record = RunMachine.finish(record, phase, failure, now), waitingReason = null)

    fun recover(now: Long = System.currentTimeMillis()): WorkerRun =
        copy(record = RunMachine.recover(record, now), waitingReason = null)

    companion object {
        fun queued(
            id: String,
            job: String,
            workspaceId: String?,
            waitingReason: String,
            now: Long = System.currentTimeMillis(),
        ): WorkerRun = WorkerRun(
            record = RunMachine.begin(id, workspaceId, job, now),
            waitingReason = waitingReason,
        )
    }
}
