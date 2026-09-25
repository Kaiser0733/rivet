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
    private var nextToken = 0L
    private val mutablePending = MutableStateFlow<AgentApprovalRequest?>(null)
    val pending: StateFlow<AgentApprovalRequest?> = mutablePending.asStateFlow()

    suspend fun await(request: AgentApprovalRequest): Boolean {
        val decision = CompletableDeferred<Boolean>()
        synchronized(lock) {
            check(active == null) { "An approval is already pending." }
            check(nextToken < Long.MAX_VALUE) { "Approval token space exhausted." }
            val identified = request.copy(approvalToken = ++nextToken)
            active = Pending(identified, decision)
            mutablePending.value = identified
        }
        return try {
            decision.await()
        } finally {
            synchronized(lock) {
                if (active?.decision === decision) {
                    active = null
                    mutablePending.value = null
                }
            }
        }
    }

    fun resolve(approvalToken: Long, approved: Boolean): Boolean {
        val pending = synchronized(lock) {
            val current = active?.takeIf { it.request.approvalToken == approvalToken } ?: return false
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
