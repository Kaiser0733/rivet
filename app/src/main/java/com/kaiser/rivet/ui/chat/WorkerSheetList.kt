package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.subagent.WorkerRecord

/**
 * The sheet lists every worker this run has, not only the one whose transcript is
 * open. Live work comes first so a queued worker is never the only visible row.
 */
internal fun workerSheetRows(workers: List<WorkerRecord>): List<WorkerRecord> {
    val seen = HashSet<String>()
    return workers
        .filter { seen.add(it.id) }
        .sortedWith(compareBy<WorkerRecord> { priority(it.state) }.thenBy { it.createdAt })
}

/** Shown beside a job name in the sheet's worker row. */
internal fun workerSheetState(worker: WorkerRecord): String = when (worker.state) {
    RunPhase.Running -> "running"
    RunPhase.Queued -> "waiting"
    RunPhase.Paused -> "paused"
    RunPhase.Done -> "done"
    RunPhase.Failed -> "failed"
    RunPhase.Stopped -> "stopped"
}

private fun priority(state: RunPhase): Int = when (state) {
    RunPhase.Running -> 0
    RunPhase.Queued -> 1
    RunPhase.Paused -> 2
    RunPhase.Done, RunPhase.Failed, RunPhase.Stopped -> 3
}
