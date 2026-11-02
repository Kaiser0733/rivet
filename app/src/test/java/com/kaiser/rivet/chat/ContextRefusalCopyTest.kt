package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// The refusal copy has to name a number the request was actually judged against. F4 plans a
// guessed window at half of Rivet's 256K default, so the guessed variants name the planned
// budget; an entered or detected window is still named as the window it is.
class ContextRefusalCopyTest {
    private val window = "200,000"
    private val planned = "115,072"

    @Test fun anEnteredOrDetectedWindowIsStillNamedAsTheWindow() {
        assertEquals(
            "Rivet compacted this chat as far as it could, and it still does not fit the 200,000-token " +
                "context window. Your messages were kept. Start a new conversation to keep going.",
            contextUnavailableMessage(window, planned, windowIsGuessed = false))
        assertEquals(
            "This model's context capacity is too small (200,000-token context window) for this request. " +
                "Choose a model with a larger context window or shorten the request.",
            contextTooSmallMessage(window, planned, windowIsGuessed = false))
    }

    @Test fun aGuessedWindowNamesTheBudgetRivetPlannedAndSaysTheWindowIsUnknown() {
        val unavailable = contextUnavailableMessage(window, planned, windowIsGuessed = true)
        assertEquals(
            "Rivet compacted this chat as far as it could, and it still does not fit the 115,072-token " +
                "budget Rivet planned for this model (no context window is known for it). Your messages " +
                "were kept. Start a new conversation to keep going.",
            unavailable)
        val tooSmall = contextTooSmallMessage(window, planned, windowIsGuessed = true)
        assertEquals(
            "This request does not fit the 115,072-token budget Rivet planned for this model " +
                "(no context window is known for it). Choose a model with a larger context window or " +
                "shorten the request.",
            tooSmall)
        // The guess is not a fact about the model, so neither sentence may quote it.
        assertFalse(unavailable.contains(window))
        assertFalse(tooSmall.contains(window))
    }

    // The entered/detected wording is the one that shipped before F4 made the guess diverge;
    // keeping it byte-identical is the contract this pins.
    @Test fun theMeasuredSentencesAreUnchanged() {
        assertEquals(
            "Rivet compacted this chat as far as it could, and it still does not fit the 262,144-token " +
                "context window. Your messages were kept. Start a new conversation to keep going.",
            contextUnavailableMessage("262,144", "246,144", windowIsGuessed = false))
        assertEquals(
            "This model's context capacity is too small (262,144-token context window) for this request. " +
                "Choose a model with a larger context window or shorten the request.",
            contextTooSmallMessage("262,144", "246,144", windowIsGuessed = false))
    }
}
