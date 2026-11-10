package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserGovernorTest {
    private val mb = 1024L * 1024L

    @Test fun tierBoundariesAreInclusiveAtTheBottom() {
        assertEquals(BrowserTier.NoRenderer, BrowserGovernor.tierFor(59 * mb))
        assertEquals(BrowserTier.ReaderFirst, BrowserGovernor.tierFor(60 * mb))
        assertEquals(BrowserTier.ReaderFirst, BrowserGovernor.tierFor(119 * mb))
        assertEquals(BrowserTier.StrictDiet, BrowserGovernor.tierFor(120 * mb))
        assertEquals(BrowserTier.StrictDiet, BrowserGovernor.tierFor(199 * mb))
        assertEquals(BrowserTier.Normal, BrowserGovernor.tierFor(200 * mb))
        assertEquals(BrowserTier.Normal, BrowserGovernor.tierFor(250 * mb))
    }

    @Test fun capIsClampedAndLeavesTheWorkingMargin() {
        val plenty = BrowserGovernor.decide(BrowserMemorySignals(2_000 * mb))
        assertEquals(250 * mb, plenty.capBytes)
        val exact = BrowserGovernor.decide(BrowserMemorySignals(300 * mb + 150 * mb + 140 * mb))
        assertEquals(140 * mb, exact.capBytes)
        val none = BrowserGovernor.decide(BrowserMemorySignals(0))
        assertEquals(0, none.capBytes)
        assertEquals(BrowserTier.NoRenderer, none.tier)
    }

    @Test fun aLoadedModelReducesTheCapByFootprintAndSafety() {
        val noModel = BrowserGovernor.decide(BrowserMemorySignals(1_000 * mb))
        val model = BrowserGovernor.decide(BrowserMemorySignals(1_000 * mb, modelFootprintBytes = 400 * mb))
        assertEquals(noModel.capBytes - 400 * mb, model.capBytes)
        assertEquals(400 * mb + 300 * mb, model.reserveBytes)
        assertTrue(model.modelLoaded)
    }

    @Test fun tiersDecideWhichActionsAreAllowed() {
        assertTrue(BrowserGovernor.canOpenRenderer(BrowserTier.Normal, interaction = true))
        assertTrue(BrowserGovernor.canOpenRenderer(BrowserTier.ReaderFirst, interaction = true))
        assertFalse(BrowserGovernor.canOpenRenderer(BrowserTier.NoRenderer, interaction = true))
        assertTrue(BrowserGovernor.canTakeScreenshot(BrowserTier.StrictDiet))
        assertFalse(BrowserGovernor.canTakeScreenshot(BrowserTier.ReaderFirst))
    }

    @Test fun sheddingHasAStableOrderAndStopsAtReader() {
        assertEquals(listOf(BrowserShedStep.Caches, BrowserShedStep.SnapshotInactive,
            BrowserShedStep.TightenDiet, BrowserShedStep.Reader), BrowserGovernor.shedPlan())
        var step: BrowserShedStep? = null
        val seen = mutableListOf<BrowserShedStep>()
        while (true) { step = BrowserGovernor.nextShed(step) ?: break; seen += step }
        assertEquals(BrowserGovernor.shedPlan(), seen)
    }

    @Test fun contextLineNamesTheTierBudgetAndModel() {
        val budget = BrowserGovernor.decide(BrowserMemorySignals(1_000 * mb, 400 * mb))
        val line = BrowserGovernor.contextLine(budget)
        assertTrue(line.contains("strict-diet"))
        assertTrue(line.contains("cap"))
        assertTrue(line.contains("local model loaded"))
    }
}
