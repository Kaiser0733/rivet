package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.subagent.WorkerRecord

internal fun workerStateLabel(worker: WorkerRecord): String = when (worker.state) {
    RunPhase.Queued -> "waiting"
    RunPhase.Running -> "running"
    RunPhase.Paused -> "paused"
    RunPhase.Done -> "done"
    RunPhase.Failed -> "failed"
    RunPhase.Stopped -> "stopped"
}

internal fun workerActivityLine(worker: WorkerRecord): String? {
    val activity = worker.activity?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        activity != null -> activity
        worker.waitingReason?.isNotBlank() == true -> worker.waitingReason
        worker.pauseReason != null -> worker.pauseReason.plain
        worker.state == RunPhase.Queued -> "waiting for a slot"
        else -> null
    }
}
