package com.kaiser.rivet.ui.history

internal enum class RecencyUnit { Now, Minute, Hour, Day }

internal data class ChatRecency(val unit: RecencyUnit, val count: Int)

internal fun chatRecency(updatedAt: Long, now: Long): ChatRecency {
    val minutes = (now.coerceAtLeast(0) - updatedAt.coerceAtLeast(0)).coerceAtLeast(0L) / 60_000L
    return when {
        minutes == 0L -> ChatRecency(RecencyUnit.Now, 0)
        minutes < 60 -> ChatRecency(RecencyUnit.Minute, minutes.toInt())
        minutes < 1440 -> ChatRecency(RecencyUnit.Hour, (minutes / 60).toInt())
        else -> ChatRecency(RecencyUnit.Day, (minutes / 1440).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
