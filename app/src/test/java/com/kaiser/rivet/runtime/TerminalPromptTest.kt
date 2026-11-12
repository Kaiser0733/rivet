package com.kaiser.rivet.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPromptTest {
    @Test fun thePromptStartsOnItsOwnLine() {
        assertTrue(TerminalPrompt.PS1.startsWith("\\n"))
    }

    @Test fun thePromptStaysFolderOnly() {
        assertFalse(TerminalPrompt.PS1.contains("\\u"))
        assertFalse(TerminalPrompt.PS1.contains("\\h"))
        assertFalse(TerminalPrompt.PS1.contains("@"))
        assertTrue(TerminalPrompt.PS1.contains("\\W"))
        assertTrue(TerminalPrompt.PS1.contains("\\$"))
    }
}
