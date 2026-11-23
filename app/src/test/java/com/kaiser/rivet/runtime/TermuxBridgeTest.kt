package com.kaiser.rivet.runtime

import org.junit.Assert.*
import org.junit.Test

class TermuxBridgeTest {
    @Test fun buildsServiceIntentWithoutSplittingCommands() {
        val intent = buildRunCommandIntent("git status", "/storage/emulated/0/Project")
        assertEquals("com.termux.RUN_COMMAND", intent.action)
        assertEquals("com.termux.app.RunCommandService", intent.service)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", intent.path)
        assertEquals(listOf("-c", "git status"), intent.arguments)
        assertEquals("/storage/emulated/0/Project", intent.cwd)
        // Termux 0.118.0 has no RUN_COMMAND_RUNNER extra; this flag is the only way to
        // reach its app-shell runner, the one runner that captures stdout and stderr.
        assertTrue(intent.background)
    }

    @Test fun rejectsInvalidCommandsAndBoundsTheTimeout() {
        assertThrows(IllegalArgumentException::class.java) { buildRunCommandIntent(" ", "") }
        assertThrows(IllegalArgumentException::class.java) { buildRunCommandIntent("a\u0000b", "") }
        assertEquals(120_000L, TermuxCommand.timeout(null))
        assertEquals(600_000L, TermuxCommand.timeout(900_000))
        assertThrows(IllegalArgumentException::class.java) { TermuxCommand.timeout(0) }
    }

    @Test fun fileFallbackQuotesShellInputAndPublishesTheExitStatusLast() {
        val wrapped = TermuxCommand.withFileOutput("echo '\$VALUE'; exit 17", "/sdcard/a b/job")
        assertTrue(wrapped.contains("folder='/sdcard/a b/job'"))
        assertTrue(wrapped.contains("exec"))
        assertTrue(wrapped.contains("exit 17"))
        assertTrue(wrapped.contains("\\''"))
        assertTrue(wrapped.contains("> \"\$folder/out.txt\" 2> \"\$folder/err.txt\""))
        assertTrue(wrapped.contains("code.tmp"))
        assertTrue(wrapped.contains("\"\$folder/code\""))
    }

    @Test fun successfulResultKeepsStdoutStderrAndExitCode() {
        val outcome = parseTermuxResult(TermuxResultBundle(
            err = 0, errmsg = null, stdout = "bridge ok 42\n", stderr = "",
            exitCode = 0, stdoutOriginalLength = "13", stderrOriginalLength = "0",
        ))
        assertEquals("bridge ok 42\n", outcome.stdout)
        assertEquals(0, outcome.exitCode)
        assertNull(outcome.error)
        assertFalse(outcome.truncated)
    }

    @Test fun stringOriginalLengthsDriveTheTruncationFlag() {
        // Termux puts these in the bundle as strings; reading them as ints always
        // produced 0 and a Bundle type warning, so truncation was never reported.
        val outcome = parseTermuxResult(TermuxResultBundle(
            err = 0, stdout = "short", exitCode = 0,
            stdoutOriginalLength = "9000", stderrOriginalLength = "0",
        ))
        assertTrue(outcome.truncated)
    }

    @Test fun termuxReportedFailureKeepsItsOwnMessage() {
        val outcome = parseTermuxResult(TermuxResultBundle(
            err = 1, errmsg = "executable not found", stdout = "", stderr = "",
        ))
        assertEquals("termux_command_failed", outcome.error)
        assertEquals("executable not found", outcome.detail)
    }

    @Test fun policyViolationStillMapsToItsSetupCode() {
        val outcome = parseTermuxResult(TermuxResultBundle(
            err = 1, errmsg = "Set allow-external-apps=true in termux.properties", stdout = "",
        ))
        assertEquals("termux_external_apps_off", outcome.error)
    }

    @Test fun missingOriginalLengthsDoNotClaimTruncation() {
        val outcome = parseTermuxResult(TermuxResultBundle(err = 0, stdout = "full", stderr = "warn"))
        assertFalse(outcome.truncated)
    }

    @Test fun errStoredAsTextStillCountsAsAFailure() {
        val outcome = parseTermuxResult(TermuxResultBundle(err = "1", errmsg = "boom"))
        assertEquals("termux_command_failed", outcome.error)
        assertEquals("boom", outcome.detail)
    }
}
