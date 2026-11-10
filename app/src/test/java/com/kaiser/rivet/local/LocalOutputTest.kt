package com.kaiser.rivet.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOutputTest {
    @Test fun stripsTheToolNarrationTheDeviceReportShowed() {
        val text = "Looking at the tools available, there's read_file, write_file, " +
            "apply_patch, etc...\nHello! How can I assist you today?"
        assertEquals("Hello! How can I assist you today?", stripUntaggedToolMusing(text))
    }

    @Test fun stripsNarrationAfterLeadingBlankLines() {
        assertEquals("Hello!", stripUntaggedToolMusing(
            "\n\nLooking at the tools available: read_file\nHello!"))
    }

    @Test fun leavesAnAnswerThatMentionsAToolAlone() {
        val text = "The read_file tool returns a SHA-256 hash."
        assertEquals(text, stripUntaggedToolMusing(text))
    }

    @Test fun aNarrationWithNoAnswerBecomesEmpty() {
        assertEquals("", stripUntaggedToolMusing("Looking at the available tools: read_file"))
    }
}
