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
        assertFalse(intent.background)
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
        assertTrue(wrapped.contains("> \"\$folder/out.txt\" 2>&1"))
        assertTrue(wrapped.contains("code.tmp"))
        assertTrue(wrapped.contains("\"\$folder/code\""))
    }
}
