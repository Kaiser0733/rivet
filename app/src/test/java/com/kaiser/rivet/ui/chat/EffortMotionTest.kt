package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.provider.ReasoningLevel
import org.junit.Assert.*
import org.junit.Test

class EffortMotionTest {
    @Test fun everyExplicitLevelHasADistinctAndIncreasingSignal() {
        assertNull(effortMotion(ReasoningLevel.Default))
        val effects = listOf(ReasoningLevel.Low, ReasoningLevel.Medium, ReasoningLevel.High,
            ReasoningLevel.XHigh, ReasoningLevel.Max, ReasoningLevel.Ultra).map { requireNotNull(effortMotion(it)) }
        assertEquals(6, effects.map { it.pattern }.distinct().size)
        assertTrue(effects.zipWithNext().all { (first, next) ->
            first.duration < next.duration && first.strength < next.strength
        })
    }
}
