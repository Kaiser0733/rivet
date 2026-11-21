package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.provider.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class EffortMotionTest {
    @Test fun explicitLevelsUseTheSameSignalWithIncreasingGraduations() {
        val levels = listOf(ReasoningLevel.Default, ReasoningLevel.Low, ReasoningLevel.Medium,
            ReasoningLevel.High, ReasoningLevel.XHigh, ReasoningLevel.Max, ReasoningLevel.Ultra)
        assertEquals((0..6).toList(), levels.map(::effortGraduations))
    }
}
