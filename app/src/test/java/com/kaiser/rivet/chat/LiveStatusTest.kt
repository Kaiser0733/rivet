package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStatusTest {

    @Test fun beforeAnyTextTheLineSaysThinking() {
        assertEquals("Thinking", liveStatusLabel(activity = null, toolRunning = false,
            writing = false, waitingForApproval = false))
    }

    @Test fun streamingTextReadsAsWriting() {
        assertEquals("Writing", liveStatusLabel(activity = "Working on it…", toolRunning = false,
            writing = true, waitingForApproval = false))
    }

    @Test fun aRunningToolShowsItsOwnActivity() {
        assertEquals("Running a project command", liveStatusLabel(activity = "Running a project command…",
            toolRunning = true, writing = true, waitingForApproval = false))
    }

    @Test fun aRunningToolWithNoActivityStillSaysSomething() {
        assertEquals("Working", liveStatusLabel(activity = null, toolRunning = true,
            writing = false, waitingForApproval = false))
        assertEquals("Working", liveStatusLabel(activity = "  …", toolRunning = true,
            writing = false, waitingForApproval = false))
    }

    @Test fun approvalOutranksEverythingElse() {
        assertEquals("Waiting for approval", liveStatusLabel(activity = "Running a project command…",
            toolRunning = true, writing = true, waitingForApproval = true))
    }

    @Test fun secondsTickUnderAMinute() {
        assertEquals("Working · 0s", liveStatusLine("Working", 0))
        assertEquals("Working · 12s", liveStatusLine("Working", 12))
        assertEquals("Thinking · 59s", liveStatusLine("Thinking", 59))
    }

    @Test fun longTurnsShowMinutes() {
        assertEquals("Running a project command · 1m 00s", liveStatusLine("Running a project command", 60))
        assertEquals("Running a project command · 10m 05s", liveStatusLine("Running a project command", 605))
    }

    @Test fun aNegativeClockNeverShows() {
        assertEquals("Working · 0s", liveStatusLine("Working", -5))
    }

    // The reasoning surface froze at 0s on device: while a chain streams, the rendering
    // work starves the one-second ticker. No counter there; the other states keep theirs.
    @Test fun theReasoningLineShowsNoClock() {
        assertFalse(liveStatusShowsClock(toolRunning = false, writing = false, waitingForApproval = false))
        assertTrue(liveStatusShowsClock(toolRunning = true, writing = false, waitingForApproval = false))
        assertTrue(liveStatusShowsClock(toolRunning = false, writing = true, waitingForApproval = false))
        assertTrue(liveStatusShowsClock(toolRunning = false, writing = false, waitingForApproval = true))
    }

    @Test fun aRunningCommandSaysWhyUndoIsUnavailable() {
        assertEquals("A command is running. Undo and retry work again when it finishes.",
            commandBusyReason(commandRunning = true))
        assertNull(commandBusyReason(commandRunning = false))
    }
}
