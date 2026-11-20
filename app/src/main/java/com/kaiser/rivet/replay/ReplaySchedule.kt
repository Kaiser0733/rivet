package com.kaiser.rivet.replay

internal data class ReplayFrameSpan(val beatIndex: Int, val firstFrame: Int, val count: Int)

internal data class ReplaySchedule(
    val fps: Int,
    val durationUs: Long,
    val spans: List<ReplayFrameSpan>,
) {
    fun timeUs(frame: Int): Long = frame * 1_000_000L / fps

    companion object {
        fun from(holdsMs: List<Long>, requestedFps: Int = 2): ReplaySchedule {
            val fps = requestedFps.coerceIn(1, 30)
            var duration = 0L
            var nextFrame = 0
            val spans = buildList {
                holdsMs.forEachIndexed { index, hold ->
                    duration += hold.coerceIn(0L, 60_000L - duration)
                    val frameEnd = ((duration * fps + 999L) / 1000L).toInt()
                    if (frameEnd > nextFrame) {
                        add(ReplayFrameSpan(index, nextFrame, frameEnd - nextFrame))
                        nextFrame = frameEnd
                    }
                }
            }
            return ReplaySchedule(fps, duration * 1000L, spans)
        }
    }
}
