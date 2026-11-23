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

    @Test fun nonEmptyBroadcastSkipsFiles() = runTest {
        val broadcast = TermuxRunResult(stdout = "immediate", exitCode = 0)
        val wait = TermuxFileWait(outputPath, read = { error("Unexpected file read") })

        assertEquals(broadcast, wait.await(CompletableDeferred(broadcast), 10_000))
    }
}
