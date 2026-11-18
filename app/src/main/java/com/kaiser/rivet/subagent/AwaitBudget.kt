package com.kaiser.rivet.subagent

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wall-clock budget that stops counting while the watched workers wait for a human
 * approval. A worker that is actually running still reaches the deadline.
 */
internal class AwaitBudget(
    private val timeoutMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var last = now()
    private var paused = false
    private var activeMs = 0L

    fun setApprovalPending(pending: Boolean) {
        val current = now()
        if (!paused) activeMs += (current - last).coerceAtLeast(0L)
        paused = pending
        last = current
    }

    fun remainingMs(): Long = (timeoutMs - activeMs).coerceAtLeast(0L)
}

/**
 * Waits until every watched worker is terminal or its active (non-approval) budget
 * runs out. Returns true when all watched workers finished, false on timeout.
 */
internal suspend fun awaitWatchedWorkers(
    state: StateFlow<List<WorkerRecord>>,
    ids: Set<String>,
    timeoutMs: Long,
    now: () -> Long = { System.nanoTime() / 1_000_000L },
): Boolean {
    if (ids.isEmpty() || timeoutMs <= 0L) return false
    val budget = AwaitBudget(timeoutMs, now)

    fun watched(): List<WorkerRecord> =
        state.value.filter { it.id in ids }

    fun allTerminal(): Boolean {
        val live = watched()
        return ids.all { id -> live.firstOrNull { it.id == id }?.isTerminal == true }
    }

    fun approvalPending(): Boolean = watched().any { it.awaitingApproval }

    while (true) {
        if (allTerminal()) return true
        val paused = approvalPending()
        budget.setApprovalPending(paused)
        val remaining = budget.remainingMs()
        if (!paused && remaining <= 0L) return false
        if (paused) {
            state.first { next ->
                val current = next.filter { it.id in ids }
                ids.all { id -> current.firstOrNull { it.id == id }?.isTerminal == true } ||
                    current.any { it.awaitingApproval } != true
            }
        } else {
            val changed = withTimeoutOrNull(remaining) {
                state.first { next ->
                    val current = next.filter { it.id in ids }
                    ids.all { id -> current.firstOrNull { it.id == id }?.isTerminal == true } ||
                        current.any { it.awaitingApproval }
                }
            }
            if (changed == null) {
                // Re-check once: the approval flag may have changed at the deadline.
                val waiting = approvalPending()
                budget.setApprovalPending(waiting)
                if (waiting) continue
                return false
            }
        }
    }
}
