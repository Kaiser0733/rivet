package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RendererPssSelectionTest {
    private val mb = 1024L * 1024L

    @Test fun aSameUidRendererProcessProvidesTheThirdSignal() {
        val processes = listOf(
            RendererProcess(100, 10_000, 40 * mb),
            RendererProcess(101, 10_000, 260 * mb),
            RendererProcess(102, 20_000, 999 * mb),
        )
        assertEquals(260 * mb, selectRendererPss(processes, ownPid = 100, ownUid = 10_000))
        val probe = BrowserMemoryProbe(
            ownPss = { 40 * mb },
            rendererPss = { selectRendererPss(processes, ownPid = 100, ownUid = 10_000) },
        )
        val growth = probe.browserGrowthBytes(0, probe.sample())
        assertEquals(260 * mb, growth)
        assertNotEquals(BrowserTier.Normal,
            BrowserGovernor.decide(BrowserMemorySignals(2_000 * mb,
                observedBrowserBytes = growth)).tier)
    }

    @Test fun noSameUidPeerMeansNoRendererSignal() {
        assertNull(selectRendererPss(listOf(RendererProcess(100, 10_000, 40 * mb)),
            ownPid = 100, ownUid = 10_000))
    }

    @Test fun theLargestSameUidPeerWins() {
        assertEquals(80 * mb, selectRendererPss(listOf(
            RendererProcess(2, 7, 80 * mb), RendererProcess(3, 7, 20 * mb),
        ), ownPid = 1, ownUid = 7))
    }
}
