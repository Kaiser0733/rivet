package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualCompactionTargetTest {
    @Test fun aSeventyNinePercentRequestMakesRoomAboveTheFixedFloor() {
        val current = 16_000L * 79 / 100
        assertEquals(11_220L, manualCompactionTarget(current, 9_800L))
        assertEquals(9_800L, manualCompactionTarget(9_700L, 9_800L))
    }
}
