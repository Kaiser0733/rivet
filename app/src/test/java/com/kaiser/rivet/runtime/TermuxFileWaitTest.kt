package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspaceFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxFileWaitTest {
    private val outputPath = "project/.rivet-termux/run/out.txt"

    @Test fun emptyBroadcastWaitsForFiles() = runTest {
        val output = TermuxRunResult(stdout = "hello-rivet\n", exitCode = 7)
        var reads = 0
        val wait = TermuxFileWait(outputPath, read = {
            if (reads++ == 0) TermuxFileRead.Missing else TermuxFileRead.Done(output)
        })

        assertEquals(output, wait.await(CompletableDeferred(TermuxRunResult(exitCode = 0)), 10_000))
        assertEquals(2, reads)
    }

    @Test fun unreadableFilesAreNotSilentSuccess() = runTest {
        val wait = TermuxFileWait(outputPath, read = { TermuxFileRead.Failed(WorkspaceFailure.Reason.PROVIDER) })

        val result = wait.await(CompletableDeferred(TermuxRunResult(exitCode = 0)), 2_000)

        assertEquals("termux_output_missing", result?.error)
        assertEquals(outputPath, result?.outputPath)
        assertTrue(result?.detail.orEmpty().contains("PROVIDER"))
        assertTrue(result?.detail.orEmpty().contains(outputPath))
    }

    @Test fun fileResultTakesPrecedenceOverNonEmptyBroadcast() = runTest {
        val broadcast = TermuxRunResult(stdout = "immediate", exitCode = 0)
        val output = TermuxRunResult(stdout = "saved", exitCode = 7)
        val wait = TermuxFileWait(outputPath, read = { TermuxFileRead.Done(output) })

        assertEquals(output, wait.await(CompletableDeferred(broadcast), 10_000))
    }

    @Test fun emptyFirstFileReadIsRetried() = runTest {
        val output = TermuxRunResult(stdout = "saved", stderr = "details", exitCode = 7)
        var reads = 0
        val wait = TermuxFileWait(outputPath, read = {
            TermuxFileRead.Done(if (reads++ == 0) TermuxRunResult(exitCode = 0) else output)
        })

        assertEquals(output, wait.await(CompletableDeferred(TermuxRunResult(exitCode = 0)), 10_000))
        assertEquals(2, reads)
    }

    @Test fun errorOnlyBroadcastDoesNotShortenTheFileDeadline() = runTest {
        val output = TermuxRunResult(stderr = "saved failure", exitCode = 2)
        var reads = 0
        val wait = TermuxFileWait(outputPath, read = {
            if (reads++ < 12) TermuxFileRead.Missing else TermuxFileRead.Done(output)
        })

        assertEquals(output, wait.await(CompletableDeferred(
            TermuxRunResult(error = "termux_command_failed", exitCode = 0),
        ), 10_000))
    }

    @Test fun permissionFailureEndsTheWaitAndReportsTheOutcome() = runTest {
        val outcomes = mutableListOf<String>()
        val wait = TermuxFileWait(outputPath,
            read = { TermuxFileRead.Failed(WorkspaceFailure.Reason.PERMISSION) },
            onReadOutcome = outcomes::add,
        )
        val result = wait.await(CompletableDeferred(), 10_000)

        assertEquals("termux_output_missing", result?.error)
        assertEquals(listOf("PERMISSION"), outcomes)
    }
}
