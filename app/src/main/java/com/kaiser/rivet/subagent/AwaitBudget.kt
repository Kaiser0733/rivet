package com.kaiser.rivet.subagent

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun awaitWatchedWorkers(
    state: StateFlow<List<WorkerRecord>>,
    ids: Set<String>,
    timeoutMs: Long,
): Boolean {
    if (ids.isEmpty() || timeoutMs <= 0L) return false
    return withTimeoutOrNull(timeoutMs) {
        state.first { records -> ids.all { id -> records.any { it.id == id && it.isTerminal } } }
    } != null
}
