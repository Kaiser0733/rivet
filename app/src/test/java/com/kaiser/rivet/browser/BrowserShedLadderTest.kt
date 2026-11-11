package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserShedLadderTest {
    @Test fun aJumpToNoRendererAnnouncesEveryStepInOrderOnAVisiblePage() {
        val actions = BrowserShedLadder.actions(BrowserTier.Normal,
            BrowserTier.NoRenderer, screenVisible = true)
        assertEquals(listOf(
            BrowserShedAction.TightenDiet,
            BrowserShedAction.UnloadFarthestWindow,
            BrowserShedAction.ReaderFirstMessage,
            BrowserShedAction.ShowBudgetCloseAction,
        ), actions)
        assertFalse(actions.contains(BrowserShedAction.DestroyHiddenRenderer))
    }

    @Test fun aHiddenPageGetsTheTeardownAsItsLastStep() {
        val actions = BrowserShedLadder.actions(BrowserTier.ReaderFirst,
            BrowserTier.NoRenderer, screenVisible = false)
        assertEquals(listOf(BrowserShedAction.DestroyHiddenRenderer), actions)
    }

    @Test fun theStrictStepAndWindowUnloadPrecedeTheReaderMessage() {
        assertEquals(listOf(BrowserShedAction.TightenDiet),
            BrowserShedLadder.actions(BrowserTier.Normal, BrowserTier.StrictDiet, true))
        assertEquals(listOf(BrowserShedAction.UnloadFarthestWindow,
            BrowserShedAction.ReaderFirstMessage),
            BrowserShedLadder.actions(BrowserTier.StrictDiet, BrowserTier.ReaderFirst, true))
    }

    @Test fun aNonEscalationHasNoLadderActions() {
        assertEquals(emptyList<BrowserShedAction>(),
            BrowserShedLadder.actions(BrowserTier.ReaderFirst, BrowserTier.ReaderFirst, true))
    }
}
