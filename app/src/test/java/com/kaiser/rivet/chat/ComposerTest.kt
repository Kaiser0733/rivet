package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerTest {

    @Test fun trailingSpaceAndNewlinesAreRemovedBeforeSending() {
        assertEquals("Fix the build", draftToSend("  Fix the build \n\n", canSend = true))
    }

    @Test fun innerLineBreaksAreKept() {
        assertEquals("first line\nsecond line", draftToSend("first line\nsecond line", canSend = true))
    }

    @Test fun nothingIsSentWhenTheComposerCannotSend() {
        assertNull(draftToSend("Fix the build", canSend = false))
    }

    @Test fun blankDraftsAreNotSent() {
        assertNull(draftToSend("   \n\t ", canSend = true))
        assertNull(draftToSend("", canSend = true))
    }

    // A draft belongs to the conversation it was typed in: switching away must not show it in
    // another chat, and switching back must still find it. Keying a rememberSaveable by the
    // session id satisfies only the first half — its contract is to revert to the init value
    // when its inputs change, so the text would be gone on the way back.
    @Test fun everyConversationKeepsItsOwnDraft() {
        var drafts = emptyMap<String, String>()
        drafts = withDraft(drafts, "session-a", "half a message")
        drafts = withDraft(drafts, "session-b", "something else")

        assertEquals("half a message", draftFor(drafts, "session-a"))
        assertEquals("something else", draftFor(drafts, "session-b"))
        assertEquals("a conversation that never typed has no draft", "", draftFor(drafts, "session-c"))
        assertEquals("an unbound conversation is its own key", "", draftFor(drafts, null))
    }

    @Test fun sendingClearsOnlyTheConversationThatSent() {
        var drafts = withDraft(emptyMap(), "session-a", "sent text")
        drafts = withDraft(drafts, "session-b", "not sent yet")

        drafts = withDraft(drafts, "session-a", "")

        assertEquals("the sent draft is gone", "", draftFor(drafts, "session-a"))
        assertEquals("the other conversation keeps its text", "not sent yet", draftFor(drafts, "session-b"))
        assertEquals("an empty draft is not kept at all", setOf("session-b"), drafts.keys)
    }
}
