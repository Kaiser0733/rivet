package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserObservedCostTest {
    private val mb = 1024L * 1024L

    @Test fun theV9OwnGrowthPlusHeadroomDropBecomesTheObservedCost() {
        val ownPssGrowth = 100 * mb
        val baselineAvail = 2_000 * mb
        val currentAvail = baselineAvail - 229 * mb
        val observed = BrowserObservedCost.bytes(ownPssGrowth, baselineAvail, currentAvail)
        assertEquals(229 * mb, observed)
        val budget = BrowserGovernor.decide(BrowserMemorySignals(2_000 * mb,
            observedBrowserBytes = observed))
        assertTrue("v9 cost must cross the reader-first tier",
            budget.tier.ordinal >= BrowserTier.ReaderFirst.ordinal)
    }

    @Test fun theParentOwnOnlyObservationStaysNormal() {
        val parentObserved = BrowserObservedCost.bytes(100 * mb, 2_000 * mb, 2_000 * mb)
        assertEquals(100 * mb, parentObserved)
        assertEquals(BrowserTier.Normal,
            BrowserGovernor.decide(BrowserMemorySignals(2_000 * mb,
                observedBrowserBytes = parentObserved)).tier)
    }

    @Test fun negativeHeadroomIsClampedToZero() {
        assertEquals(75 * mb, BrowserObservedCost.bytes(75 * mb, 500 * mb, 700 * mb))
    }

}
