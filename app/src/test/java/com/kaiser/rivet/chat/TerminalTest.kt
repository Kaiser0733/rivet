package com.kaiser.rivet.chat

import com.kaiser.rivet.runtime.RuntimeCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    @Test fun streamsJoinWithOneNewlineBetweenThem() {
        assertEquals("out\nerr", terminalText("out", "err"))
        assertEquals("out\nerr\n", terminalText("out\n", "err\n"))
        assertEquals("err", terminalText("", "err"))
        assertEquals("", terminalText("", ""))
    }

    @Test fun controlCharactersAreReplacedButTabsAndNewlinesStay() {
        assertEquals("a\uFFFDb\n\tc", terminalText("a\u0001b\n\tc", ""))
    }

    @Test fun statusNamesTheGovernorStops() {
        assertTrue(terminalStatus(result(timedOut = true), false).contains("timed out"))
        assertTrue(terminalStatus(result(tooManyChildren = true), false)
            .contains("too many child processes"))
        assertEquals("Stopped.", terminalStatus(null, cancelled = true))
    }

    @Test fun statusNamesExitCodesAndRuntimeRefusals() {
        assertEquals("Finished.", terminalStatus(result(exitCode = 0), false))
        assertEquals("Exit 7.", terminalStatus(result(exitCode = 7), false))
        assertTrue(terminalStatus(result(error = "workspace_unavailable"), false)
            .contains("No workspace"))
        assertTrue(terminalStatus(result(error = "process_limit"), false).contains("Four processes"))
        assertTrue(terminalStatus(result(error = "sync_required"), false).contains("syncing"))
    }

    private fun result(exitCode: Int? = 0, timedOut: Boolean = false,
                       tooManyChildren: Boolean = false, error: String? = null) =
        RuntimeCommandResult(
            exitCode = exitCode,
            sync = "ok",
            timedOut = timedOut,
            tooManyChildren = tooManyChildren,
            error = error,
        )
}
