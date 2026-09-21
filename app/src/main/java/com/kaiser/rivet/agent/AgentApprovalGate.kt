package com.kaiser.rivet.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentApprovalGate {
    private data class Pending(
        val request: AgentApprovalRequest,
        val decision: CompletableDeferred<Boolean>,
    )

    private val lock = Any()
    private var active: Pending? = null
    private val mutablePending = MutableStateFlow<AgentApprovalRequest?>(null)
    val pending: StateFlow<AgentApprovalRequest?> = mutablePending.asStateFlow()

    suspend fun await(request: AgentApprovalRequest): Boolean {
        val pending = Pending(request, CompletableDeferred())
        synchronized(lock) {
            check(active == null) { "An approval is already pending." }
            active = pending
            mutablePending.value = request
        }
        return try {
            pending.decision.await()
        } finally {
            synchronized(lock) {
                if (active === pending) {
                    active = null
                    mutablePending.value = null
                }
            }
        }
    }

    fun resolve(callId: String, approved: Boolean): Boolean {
        val pending = synchronized(lock) {
            val current = active?.takeIf { it.request.call.id == callId } ?: return false
            active = null
            mutablePending.value = null
            current
        }
        return pending.decision.complete(approved)
    }

    fun cancel() {
        val pending = synchronized(lock) {
            val current = active ?: return
            active = null
            mutablePending.value = null
            current
        }
        pending.decision.cancel()
    }
}
