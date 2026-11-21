package com.kaiser.rivet.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageDeltaTest {
    @Test fun preservesReportedBaselineAndPricesBothDirections() {
        assertEquals(1200L, repriceContextUsage(1200, 1000, 1000))
        assertEquals(1400L, repriceContextUsage(1200, 1000, 1200))
        assertEquals(500L, repriceContextUsage(1200, 1000, 300))
        assertEquals(0L, repriceContextUsage(200, 1000, 100))
    }
}
