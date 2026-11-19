package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentChipPolicyTest {
    @Test fun theChipTapOpensTheNewestWorkerSheet() {
        assertNull(chipSheetWorker(emptyList<String>()))
        assertEquals("w2", chipSheetWorker(listOf("w1", "w2")))
        assertEquals("w1", chipSheetWorker(listOf("w1")))
    }

    @Test fun theChipLabelNamesTheWorkerCount() {
        assertNull(subagentChipText(0))
        assertEquals("1 subagent", subagentChipText(1))
        assertEquals("3 subagents", subagentChipText(3))
    }
}
