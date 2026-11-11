package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Baseline defect evidence: the parent's own-only 100MB observation stays Normal. */
class ParentObservedTierTest {
    private val mb = 1024L * 1024L

    @Test fun ownProcessGrowthOf100MbStaysNormalOnTheParent() {
        val budget = BrowserGovernor.decide(BrowserMemorySignals(2_000 * mb,
            observedBrowserBytes = 100 * mb))
        assertEquals(BrowserTier.Normal, budget.tier)
    }
}
