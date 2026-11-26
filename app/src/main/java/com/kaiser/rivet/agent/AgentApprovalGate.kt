package com.kaiser.rivet.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

internal class ApprovalUnavailable(val code: String) : Exception(code)

class AgentApprovalGate(
    private val identify: (AgentApprovalRequest) -> AgentApprovalRequest = { it },
) {
    private data class Pending(
        val owner: Any?,
        val request: AgentApprovalRequest,
        val decision: CompletableDeferred<Boolean>,
        val expiresAt: Long,
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
            val identified = identify(request).copy(approvalToken = ++nextToken)
            queue.addLast(Pending(owner, identified, decision, System.currentTimeMillis() + TIMEOUT_MS))
            mutablePending.value = queue.firstOrNull()?.request
        }
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { decision.await() }
                ?: throw ApprovalUnavailable("approval_expired")
        } finally {
            synchronized(lock) {
                if (queue.removeAll { it.decision === decision }) {
                    mutablePending.value = queue.firstOrNull()?.request
                }
            }
        }
    }

    suspend fun grant(approvalToken: Long): Boolean {
        val current = synchronized(lock) {
            queue.firstOrNull()?.takeIf { it.request.approvalToken == approvalToken }
        } ?: return false
        val currentDetails = try { current.request.reviewAtGrant?.invoke() != false }
        catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        return resolve(approvalToken, approved = true,
            rejection = if (currentDetails) null else "approval_changed")
    }

    fun resolve(approvalToken: Long, approved: Boolean): Boolean =
        resolve(approvalToken, approved, rejection = null)

    private fun resolve(approvalToken: Long, approved: Boolean, rejection: String?): Boolean {
        val head = synchronized(lock) {
            val current = queue.firstOrNull()?.takeIf { it.request.approvalToken == approvalToken } ?: return false
            queue.removeFirst()
            mutablePending.value = queue.firstOrNull()?.request
            current
        }
        val reason = if (System.currentTimeMillis() >= head.expiresAt) "approval_expired" else rejection
        if (reason != null) {
            head.decision.completeExceptionally(ApprovalUnavailable(reason))
            return false
        }
        return head.decision.complete(approved)
    }

    fun cancel(owner: Any) = cancelMatching { it.owner == owner }

    fun cancelSession(sessionId: String?) = cancelMatching { it.request.sessionId == sessionId }

    private fun cancelMatching(matches: (Pending) -> Boolean) {
        val removed = synchronized(lock) {
            val matching = queue.filter(matches)
            if (matching.isEmpty()) return
            queue.removeAll(matches)
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

    companion object { const val TIMEOUT_MS = 120_000L }
}
