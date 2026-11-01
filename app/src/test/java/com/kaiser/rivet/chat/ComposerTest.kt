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
}
