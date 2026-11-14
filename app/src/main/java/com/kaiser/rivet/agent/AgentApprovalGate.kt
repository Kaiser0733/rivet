package com.kaiser.rivet.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentApprovalGate {
    private data class Pending(
        val owner: Any?,
        val request: AgentApprovalRequest,
        val decision: CompletableDeferred<Boolean>,
    )

    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private var nextToken = 0L
    private val mutablePending = MutableStateFlow<AgentApprovalRequest?>(null)
    val pending: StateFlow<AgentApprovalRequest?> = mutablePending.asStateFlow()

    suspend fun await(request: AgentApprovalRequest): Boolean = await(request, owner = null)

    suspend fun await(request: AgentApprovalRequest, owner: Any?): Boolean {
        val decision = CompletableDeferred<Boolean>()
        synchronized(lock) {
            check(nextToken < Long.MAX_VALUE) { "Approval token space exhausted." }
            val identified = request.copy(approvalToken = ++nextToken)
            queue.addLast(Pending(owner, identified, decision))
            mutablePending.value = queue.firstOrNull()?.request
        }
        return try {
            decision.await()
        } finally {
            synchronized(lock) {
                if (queue.removeAll { it.decision === decision }) {
                    mutablePending.value = queue.firstOrNull()?.request
                }
            }
        }
    }

    fun resolve(approvalToken: Long, approved: Boolean): Boolean {
        val head = synchronized(lock) {
            val current = queue.firstOrNull()?.takeIf { it.request.approvalToken == approvalToken } ?: return false
            queue.removeFirst()
            mutablePending.value = queue.firstOrNull()?.request
            current
        }
        return head.decision.complete(approved)
    }

    fun cancel(owner: Any) {
        val removed = synchronized(lock) {
            val matching = queue.filter { it.owner == owner }
            if (matching.isEmpty()) return
            queue.removeAll { it.owner == owner }
            mutablePending.value = queue.firstOrNull()?.request
            matching
        }
        removed.forEach { it.decision.cancel() }
    }

    fun cancel() {
        val all = synchronized(lock) {
            if (queue.isEmpty()) return
            val snapshot = queue.toList()
            queue.clear()
            mutablePending.value = null
            snapshot
        }
        all.forEach { it.decision.cancel() }
    }
}
