package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompactionTest {
    @Test fun aRunningReplyBlocksTheManualTrigger() {
        assertEquals(CompactTrigger.RunActive, compactTrigger(streaming = true, hasMessages = true))
        assertEquals(CompactTrigger.RunActive, compactTrigger(streaming = true, hasMessages = false))
        assertFalse(compactRowEnabled(streaming = true))
    }

    @Test fun anEmptySessionIsAPlainNoOp() {
        assertEquals(CompactTrigger.NothingToCompact, compactTrigger(streaming = false, hasMessages = false))
    }

    @Test fun anIdleSessionWithHistoryFiresAndTheRowIsEnabled() {
        assertEquals(CompactTrigger.Fire, compactTrigger(streaming = false, hasMessages = true))
        assertTrue(compactRowEnabled(streaming = false))
    }
}
