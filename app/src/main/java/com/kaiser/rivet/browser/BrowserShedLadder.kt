package com.kaiser.rivet.browser

/** Ordered, visible memory steps. NoRenderer never silently kills a visible page. */
enum class BrowserShedAction {
    TightenDiet,
    UnloadFarthestWindow,
    ReaderFirstMessage,
    ShowBudgetCloseAction,
    DestroyHiddenRenderer,
}

object BrowserShedLadder {
    fun actions(previous: BrowserTier, next: BrowserTier, screenVisible: Boolean):
        List<BrowserShedAction> {
        if (next.ordinal <= previous.ordinal) return emptyList()
        val actions = mutableListOf<BrowserShedAction>()
        if (previous.ordinal < BrowserTier.StrictDiet.ordinal &&
            next.ordinal >= BrowserTier.StrictDiet.ordinal) {
            actions += BrowserShedAction.TightenDiet
        }
        if (previous.ordinal < BrowserTier.ReaderFirst.ordinal &&
            next.ordinal >= BrowserTier.ReaderFirst.ordinal) {
            actions += BrowserShedAction.UnloadFarthestWindow
            actions += BrowserShedAction.ReaderFirstMessage
        }
        if (next == BrowserTier.NoRenderer) {
            actions += if (screenVisible) BrowserShedAction.ShowBudgetCloseAction
            else BrowserShedAction.DestroyHiddenRenderer
        }
        return actions
    }
}
