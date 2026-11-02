package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Kaiser's ruling on the device, 2026-11-02: the refusal banner has to read as plain human words.
// No numbers, no "budget", no "token", no "context window", and no sending a user who has worked
// for hours off to a new conversation. These pins are the wording, and the words that must never
// come back into it.
class ContextRefusalCopyTest {
    private val family = listOf(contextUnavailableMessage(), contextTooSmallMessage(), ChatViewModel.CONTEXT_LIMIT_ERROR)

    @Test fun aRefusalSaysWhatHappenedThatNothingWasLostAndOneThingToTry() {
        assertEquals(
            "This chat is too long for the model to read all at once, even after Rivet shortened it. " +
                "Your messages are safe. Try again, and if it keeps stopping here, remove the longest message.",
            contextUnavailableMessage())
        assertEquals(
            "The newest message is too big for this model to read. Your earlier messages are safe. " +
                "Shorten it and send it again.",
            contextTooSmallMessage())
        assertEquals(
            "This chat is full. Your messages are safe. Start a new chat to keep working.",
            ChatViewModel.CONTEXT_LIMIT_ERROR)
    }

    @Test fun noRefusalQuotesANumberOrSpeaksJargon() {
        for (message in family) {
            assertFalse("a refusal must not quote a number: $message", message.any { it.isDigit() })
            for (word in listOf("token", "budget", "context window", "window", "capacity", "model context")) {
                assertFalse("a refusal must not say \"$word\": $message",
                    message.contains(word, ignoreCase = true))
            }
        }
    }

    @Test fun noRefusalTellsTheUserToAbandonTheConversation() {
        // The session cap is the one case that genuinely needs a new chat, and it says "chat", not
        // "conversation", and never in the imperative that the old dead-end used.
        assertFalse(contextUnavailableMessage().contains("new conversation"))
        assertFalse(contextTooSmallMessage().contains("new conversation"))
        assertFalse(ChatViewModel.CONTEXT_LIMIT_ERROR.contains("new conversation"))
    }
}
