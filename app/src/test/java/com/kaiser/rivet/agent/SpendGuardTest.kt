package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendGuardTest {
    private val usage = AgentUsage(inputTokens = 1_000_000, outputTokens = 100_000,
        cacheReadTokens = 200_000)

    @Test fun knownAndUnknownPricingDegradeHonestly() {
        assertEquals(2.5 + 1.0 + 0.5, SpendGuard.cost(usage,
            PricingTable.price("gpt-4o"))!!, 0.0001)
        assertNull(SpendGuard.cost(usage, PricingTable.price("made-up-model")))
        assertEquals("unknown pricing", SpendGuard.estimateRange(null, 1..2, 1..2))
        assertTrue(SpendGuard.estimateRange(PricingTable.price("gpt-4o"), 1000..2000, 100..200)
            .contains("estimate"))
    }

    @Test fun capsPauseAtTheCapAndAreRaiseable() {
        val cost = SpendGuard.cost(usage, PricingTable.price("gpt-4o"))!!
        val low = SpendGuard.evaluate(cost, cost, SpendGuard.tokens(usage), SpendGuard.tokens(usage),
            SpendCaps(perRunUsd = 0.5, monthlyUsd = 100.0))
        assertTrue(low.paused)
        assertEquals(SpendStop.PerRun, low.stop)
        assertEquals("budget reached — raise or stop", low.message)

        val raised = SpendGuard.evaluate(cost, cost, SpendGuard.tokens(usage), SpendGuard.tokens(usage),
            SpendCaps(perRunUsd = 10.0, monthlyUsd = 100.0))
        assertFalse(raised.paused)
    }

    @Test fun localRunsNeverSpendAndTokenCapsWorkWithoutPrices() {
        val local = SpendGuard.evaluate(99.0, 99.0, 1_000_000, 1_000_000, SpendCaps(1.0, 1.0), localModel = true)
        assertFalse(local.paused)
        val tokens = SpendGuard.evaluate(null, null, 10_000, 10_000,
            SpendCaps(perRunTokens = 9_000))
        assertEquals(SpendStop.PerRun, tokens.stop)
        assertEquals("$1.23", SpendGuard.liveLabel(1.23))
        assertNull(SpendGuard.liveLabel(0.0))
    }
}
