package com.kaiser.rivet.ui.chat

fun formatRunDuration(durationMs: Long): String {
    val clamped = maxOf(1000L, durationMs)
    val totalSeconds = clamped / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L

    val sb = StringBuilder()
    if (hours > 0) sb.append("${hours}h")
    if (totalSeconds >= 60) sb.append("${minutes}m")
    sb.append("${seconds}s")
    return sb.toString()
}

data class ChatTurnProcessSpec(
    val turnIndex: Int,
    val isLive: Boolean = false,
    val isStopped: Boolean = false,
    val isError: Boolean = false,
    val durationMs: Long? = null,
    val hasInterleavedUserInput: Boolean = false,
) {
    val canFold: Boolean
        get() = !isLive && !isStopped && !isError && !hasInterleavedUserInput

    fun foldLabel(): String = when {
        isStopped -> "Stopped"
        isError -> "Failed"
        durationMs != null -> "Completed in ${formatRunDuration(durationMs)}"
        else -> "Completed"
    }
}

data class TurnItem<T>(
    val data: T,
    val isUser: Boolean,
    val isAnswer: Boolean,
    val isProcess: Boolean,
)

sealed interface TurnPart<out T> {
    data class Process<T>(val items: List<T>) : TurnPart<T>
    data class Answer<T>(val item: T) : TurnPart<T>
}

data class SegmentedTurn<T>(
    val turnIndex: Int,
    val userItem: T?,
    val parts: List<TurnPart<T>>,
    val spec: ChatTurnProcessSpec,
) {
    val processItems: List<T>
        get() = parts.filterIsInstance<TurnPart.Process<T>>()
            .flatMap { it.items }
    val answerItems: List<T>
        get() = parts.filterIsInstance<TurnPart.Answer<T>>()
            .map { it.item }
    val answerItem: T? get() = answerItems.lastOrNull()
}

fun <T> segmentTurnItems(
    items: List<TurnItem<T>>,
    isStreaming: Boolean,
    turnDurationsMs: Map<Int, Long>,
    isInterruptedTurn: Boolean,
    hasError: Boolean,
): List<SegmentedTurn<T>> {
    val result = mutableListOf<SegmentedTurn<T>>()
    var currentTurnIndex = 0
    var userTurnIndex = 0
    var currentUser: T? = null
    val currentParts = mutableListOf<TurnPart<T>>()
    val pendingProcess = mutableListOf<T>()

    fun flushProcess() {
        if (pendingProcess.isNotEmpty()) {
            currentParts += TurnPart.Process(pendingProcess.toList())
            pendingProcess.clear()
        }
    }

    fun flushTurn(isLast: Boolean, interleaved: Boolean = false) {
        flushProcess()
        if (currentUser == null && currentParts.isEmpty()) return
        val isLive = isLast && isStreaming
        val isStopped = isLast && isInterruptedTurn
        val isError = isLast && hasError
        // Match turnDurationsMs (keyed by User turn ordinal) without letting orphan pre-user slices shift keys.
        val duration = if (currentUser != null) turnDurationsMs[userTurnIndex++] else null
        val spec = ChatTurnProcessSpec(
            turnIndex = currentTurnIndex,
            isLive = isLive,
            isStopped = isStopped,
            isError = isError,
            durationMs = duration,
            hasInterleavedUserInput = interleaved,
        )
        result.add(
            SegmentedTurn(
                turnIndex = currentTurnIndex,
                userItem = currentUser,
                parts = currentParts.toList(),
                spec = spec,
            ),
        )
        currentTurnIndex++
        currentUser = null
        currentParts.clear()
    }

    items.forEach { item ->
        if (item.isUser) {
            val interleaved = (currentParts.none { it is TurnPart.Answer }) &&
                (currentParts.isNotEmpty() || pendingProcess.isNotEmpty())
            flushProcess()
            if (currentUser != null || currentParts.isNotEmpty()) {
                flushTurn(isLast = false, interleaved = interleaved)
            }
            currentUser = item.data
        } else if (item.isAnswer) {
            flushProcess()
            currentParts += TurnPart.Answer(item.data)
        } else if (item.isProcess) {
            pendingProcess += item.data
        }
    }
    flushTurn(isLast = true)
    return result
}

internal data class RemappedTurnMetadata(
    val reasoning: Map<Int, String>,
    val durations: Map<Int, Long>,
)

internal fun <T> remapTurnMetadataAfterVisibilityChange(
    oldMessages: List<T>,
    newMessages: List<T>,
    oldReasoning: Map<Int, String>,
    oldDurations: Map<Int, Long>,
    isInternal: (T) -> Boolean,
    isUser: (T) -> Boolean,
    sameMessage: (T, T) -> Boolean,
): RemappedTurnMetadata {
    val oldVis = oldMessages.mapIndexedNotNull { idx, msg ->
        if (!isInternal(msg)) IndexedValue(idx, msg) else null
    }
    val newVis = newMessages.mapIndexedNotNull { idx, msg ->
        if (!isInternal(msg)) IndexedValue(idx, msg) else null
    }
    if (oldVis.isEmpty() || newVis.isEmpty()) {
        return RemappedTurnMetadata(emptyMap(), emptyMap())
    }
    var oldEnd = oldVis.size
    val newEnd = newVis.size
    if (oldVis.size >= 2 &&
        !sameMessage(oldVis.last().value, newVis.last().value) &&
        sameMessage(oldVis[oldVis.size - 2].value, newVis.last().value)
    ) {
        oldEnd = oldVis.size - 1
    }
    var matchedLen = 0
    val maxMatch = minOf(oldEnd, newEnd)
    while (matchedLen < maxMatch &&
        sameMessage(oldVis[oldEnd - 1 - matchedLen].value, newVis[newEnd - 1 - matchedLen].value)
    ) {
        matchedLen++
    }
    if (matchedLen == 0) {
        return RemappedTurnMetadata(emptyMap(), emptyMap())
    }
    val oldStart = oldEnd - matchedLen
    val newStart = newEnd - matchedLen
    val oldToNewIndex = HashMap<Int, Int>(matchedLen)
    for (k in 0 until matchedLen) {
        oldToNewIndex[oldVis[oldStart + k].index] = newVis[newStart + k].index
    }
    val nextReasoning = oldReasoning.mapNotNull { (oldIdx, text) ->
        oldToNewIndex[oldIdx]?.let { newIdx -> newIdx to text }
    }.toMap()
    val droppedUsers = oldVis.subList(0, oldStart).count { isUser(it.value) }
    val nextDurations = oldDurations.mapNotNull { (turnIdx, ms) ->
        val nextTurn = turnIdx - droppedUsers
        if (nextTurn >= 0) nextTurn to ms else null
    }.toMap()
    return RemappedTurnMetadata(nextReasoning, nextDurations)
}
