package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMemoryGovernorTest {
    private val mb = 1024L * 1024L

    @Test fun observedGrowthRaisesTheVisibleTier() {
        assertEquals(BrowserTier.StrictDiet, BrowserGovernor.observedTier(120 * mb, BrowserMemoryLimit.Mb250))
        assertEquals(BrowserTier.ReaderFirst, BrowserGovernor.observedTier(200 * mb, BrowserMemoryLimit.Mb250))
        assertEquals(BrowserTier.NoRenderer, BrowserGovernor.observedTier(250 * mb, BrowserMemoryLimit.Mb250))
        val budget = BrowserGovernor.decide(BrowserMemorySignals(2_000 * mb,
            observedBrowserBytes = 220 * mb), BrowserMemoryLimit.Mb250)
        assertEquals(BrowserTier.ReaderFirst, budget.tier)
        assertEquals(220, budget.observedMb)
    }

    @Test fun theLargerLimitUsesTheLargerObservedMemoryThresholds() {
        assertEquals(BrowserTier.Normal, BrowserGovernor.observedTier(250 * mb, BrowserMemoryLimit.Gb2))
        assertEquals(BrowserTier.StrictDiet, BrowserGovernor.observedTier(1000 * mb, BrowserMemoryLimit.Gb2))
        assertEquals(BrowserTier.ReaderFirst, BrowserGovernor.observedTier(1700 * mb, BrowserMemoryLimit.Gb2))
        assertEquals(BrowserTier.NoRenderer, BrowserGovernor.observedTier(2048 * mb, BrowserMemoryLimit.Gb2))
    }

    @Test fun theProbeUsesMeasuredOwnGrowthAndNeverInventsRendererPss() {
        val probe = BrowserMemoryProbe(ownPss = { 500 * mb }, rendererPss = { null })
        val sample = probe.sample()
        assertEquals(500 * mb, sample.ownPssBytes)
        assertEquals(null, sample.rendererPssBytes)
        assertEquals(200 * mb, probe.browserGrowthBytes(300 * mb, sample))
    }

    @Test fun platformRendererPssWinsWhenItIsLarger() {
        val probe = BrowserMemoryProbe(ownPss = { 500 * mb }, rendererPss = { 180 * mb })
        assertEquals(180 * mb, probe.browserGrowthBytes(400 * mb, probe.sample()))
    }
}
