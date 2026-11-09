package com.kaiser.rivet.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Notification actions reach the running turn through these in-process seams. */
object AgentRunControl {
    private val mutablePauses = MutableSharedFlow<RunPauseReason>(extraBufferCapacity = 1)
    val pauses: SharedFlow<RunPauseReason> = mutablePauses.asSharedFlow()
    fun requestPause(reason: RunPauseReason = RunPauseReason.User) {
        mutablePauses.tryEmit(reason)
    }
}

object NotificationApprovals {
    @Volatile var gate: AgentApprovalGate? = null

    fun resolve(token: Long, approved: Boolean): Boolean =
        gate?.resolve(token, approved) ?: false
}
