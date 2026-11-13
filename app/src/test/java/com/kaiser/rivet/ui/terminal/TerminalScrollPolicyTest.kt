package com.kaiser.rivet.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalScrollPolicyTest {
    @Test
    fun scrollsCursorIntoViewWhenImeShrinksViewportWithZeroTranscriptRows() {
        val target = TerminalScrollPolicy.cursorScrollTargetRow(
            follow = true,
            transcriptRows = 0,
            totalRows = 24,
            cursorIndex = 18,
            visibleRows = 12,
        )
        assertEquals(18, target)
    }

    @Test
    fun leavesPromptAtTopWhenScreenFitsWithoutScrollback() {
        assertNull(
            TerminalScrollPolicy.cursorScrollTargetRow(
                follow = true,
                transcriptRows = 0,
                totalRows = 24,
                cursorIndex = 4,
                visibleRows = 24,
            ),
        )
    }
}
