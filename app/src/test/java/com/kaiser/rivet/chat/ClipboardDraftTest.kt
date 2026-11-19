package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardDraftTest {
    @Test fun emptyClipboardKeepsTheDraft() {
        for (clip in listOf(null, "", " \n")) assertEquals("typed", appendClipboardText("typed", clip))
    }

    @Test fun sharedClipboardTextAppendsToTheCurrentDraftUnedited() {
        assertEquals("typed\n  pasted\n", appendClipboardText("typed", "  pasted\n"))
        assertEquals("typed\npasted", appendClipboardText("typed\n", "pasted"))
        assertEquals("pasted", appendClipboardText("", "pasted"))
    }
}
