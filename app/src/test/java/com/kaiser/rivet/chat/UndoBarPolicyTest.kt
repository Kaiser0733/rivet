package com.kaiser.rivet.chat

import com.kaiser.rivet.runtime.CheckpointRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoBarPolicyTest {
    private fun record(session: String? = "session-a", dismissed: Boolean = false) =
        CheckpointRecord(id = "checkpoint", workspace = "workspace", createdAt = 1,
            before = emptyMap(), sessionId = session, barDismissed = dismissed)

    @Test fun aLegacyRecordWithoutASessionNeverOwnsTheBar() {
        assertFalse(undoBarVisible(record(session = null), "session-a"))
    }

    @Test fun onlyTheOwningSessionShowsTheBar() {
        assertTrue(undoBarVisible(record(), "session-a"))
        assertFalse(undoBarVisible(record(), "session-b"))
    }

    @Test fun aDismissedRecordNeverReappears() {
        assertFalse(undoBarVisible(record(dismissed = true), "session-a"))
    }

    @Test fun oldJsonDecodesWithTheSafeDefaults() {
        val old = """{"id":"old","workspace":"w","createdAt":1,"before":{}}"""
        val decoded = Json.decodeFromString<CheckpointRecord>(old)
        assertNull(decoded.sessionId)
        assertFalse(decoded.barDismissed)
        assertFalse(undoBarVisible(decoded, "any"))
    }
}
