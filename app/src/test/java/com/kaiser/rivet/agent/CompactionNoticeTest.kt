package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionNoticeTest {
    @Test fun theMarkerSaysWhatHappenedInPlainWords() {
        assertEquals("Compacted to 2149 tokens", CompactionNotice.completed(2149))
        assertEquals("Conversation compacted",
            CompactionNotice.display("Earlier turns were summarised to fit the model's window."))
    }

    @Test fun onlyTheCompactionKindIsANotice() {
        assertTrue(CompactionNotice.isNotice(CompactionNotice.KIND))
        assertFalse(CompactionNotice.isNotice("summary"))
        assertFalse(CompactionNotice.isNotice(null))
    }

    @Test fun theRestoreNoticeSaysTheScrollbackWasCleared() {
        assertEquals("Terminal restarted by restore — earlier scrollback was cleared.",
            CompactionNotice.RESTORE_TEXT)
        assertTrue(CompactionNotice.isNotice(CompactionNotice.RESTORE_KIND))
    }
}
