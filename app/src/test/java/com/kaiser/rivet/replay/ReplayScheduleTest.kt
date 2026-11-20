package com.kaiser.rivet.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayScheduleTest {
    @Test fun schedulesEachBeatInPresentationOrderWithoutRoundingDrift() {
        val schedule = ReplaySchedule.from(listOf(1001L, 938L, 2178L), 24)
        assertEquals(4_117_000L, schedule.durationUs)
        assertEquals(listOf(25, 22, 52), schedule.spans.map { it.count })
        val times = schedule.spans.flatMap { span ->
            (span.firstFrame until span.firstFrame + span.count).map(schedule::timeUs)
        }
        assertEquals(99, times.size)
        assertEquals(0L, times.first())
        assertEquals(1_041_666L, schedule.timeUs(schedule.spans[1].firstFrame))
        assertTrue(times.zipWithNext().all { (before, after) -> after > before })
        assertTrue(times.last() < schedule.durationUs)
    }

    @Test fun clampsFpsAndDurationAndIgnoresEmptyHolds() {
        val schedule = ReplaySchedule.from(listOf(0L, -1L, 30_000L, 90_000L, 1000L), 90)
        assertEquals(30, schedule.fps)
        assertEquals(60_000_000L, schedule.durationUs)
        assertEquals(listOf(2, 3), schedule.spans.map { it.beatIndex })
        assertEquals(1800, schedule.spans.sumOf { it.count })
        assertEquals(1, ReplaySchedule.from(listOf(30_000L), 0).fps)
    }
}
