package com.kaiser.rivet.subagent

import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.workspace.WorkspacePath

data class SubagentRequest(
    val job: String,
    val model: ProviderConfig,
    val writeScope: Collection<WorkspacePath> = emptySet(),
) {
    val local: Boolean get() = model.type == ProviderType.Local
}

/** A worker that currently owns a slot and/or a write scope. */
data class SubagentLive(
    val job: String,
    val model: ProviderConfig,
    val writeScope: Collection<WorkspacePath> = emptySet(),
    val running: Boolean = true,
) {
    val local: Boolean get() = model.type == ProviderType.Local
}

enum class QueueReason(val wire: String) {
    Slot("slot"),
    Files("files"),
}

sealed interface Admission {
    data object Start : Admission
    data class Queue(val reason: QueueReason, val detail: String) : Admission
    data class Refuse(val line: String) : Admission
}

/**
 * Admits one spawn against the live workers. File ownership is checked before
 * the slot cap because overlapping work must wait even when a slot is free.
 * Releasing a slot is simply passing a terminal worker as not-running.
 */
object SubagentScheduler {
    const val MAX_RUNNING = 2
    const val MAX_LOCAL = 1

    fun admit(
        request: SubagentRequest,
        live: List<SubagentLive>,
        coordinatorConfig: ProviderConfig,
    ): Admission {
        val running = live.filter { it.running }
        val localLive = running.filter { it.local }

        if (request.local) {
            val coordinatorLocal = coordinatorConfig.type == ProviderType.Local
            if (coordinatorLocal && request.model.model != coordinatorConfig.model) {
                return Admission.Refuse(
                    "Local worker '${request.model.model}' would unload the coordinator's " +
                        "model '${coordinatorConfig.model}'; pick a cloud model or the " +
                        "coordinator's model."
                )
            }
            if (localLive.size >= MAX_LOCAL) {
                return Admission.Refuse(
                    "This phone runs one local model at a time; pick a cloud model or " +
                        "wait for ${localLive.first().job}."
                )
            }
        }

        val overlapping = running.firstOrNull { other ->
            request.writeScope.isNotEmpty() &&
                other.writeScope.isNotEmpty() &&
                FileScopeRegistry.overlaps(request.writeScope, other.writeScope)
        }
        if (overlapping != null) {
            return Admission.Queue(QueueReason.Files, "same files as ${overlapping.job}")
        }

        if (running.size >= MAX_RUNNING) {
            return Admission.Queue(QueueReason.Slot, "$MAX_RUNNING workers are already running")
        }
        return Admission.Start
    }
}
