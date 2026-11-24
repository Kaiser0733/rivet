package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspaceFailure
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal data class TermuxRunResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
    val detail: String? = null,
    val outputPath: String? = null,
    val truncated: Boolean = false,
)


internal sealed interface TermuxFileRead {
    data object Missing : TermuxFileRead
    data class Failed(val reason: WorkspaceFailure.Reason) : TermuxFileRead
    data class Done(val result: TermuxRunResult) : TermuxFileRead
}

internal class TermuxFileWait(
    private val outputPath: String,
    private val read: suspend () -> TermuxFileRead,
    private val pollMillis: Long = 500,
    private val onReadOutcome: (String) -> Unit = {},
) {
    suspend fun await(returned: Deferred<TermuxRunResult>, timeoutMillis: Long): TermuxRunResult? {
        var lastRead: TermuxFileRead = TermuxFileRead.Missing
        val result = withTimeoutOrNull(timeoutMillis) {
            awaitFiles { lastRead = it }
        }
        val outcome = when (val read = lastRead) {
            TermuxFileRead.Missing -> "MISSING"
            is TermuxFileRead.Failed -> read.reason.name
            is TermuxFileRead.Done -> "DONE exit=${read.result.exitCode} " +
                "stdout=${read.result.stdout.length} stderr=${read.result.stderr.length}"
        }
        onReadOutcome(outcome)
        return result ?: if (returned.isCompleted || lastRead != TermuxFileRead.Missing) {
            missingOutput(outcome)
        } else null
    }

    private suspend fun awaitFiles(onRead: (TermuxFileRead) -> Unit): TermuxRunResult {
        while (true) {
            val attempt = read()
            onRead(attempt)
            when (attempt) {
                TermuxFileRead.Missing -> Unit
                is TermuxFileRead.Failed -> if (!attempt.reason.retryableTermuxRead()) {
                    return missingOutput(attempt.reason.name)
                }
                is TermuxFileRead.Done -> if (attempt.result.stdout.isNotEmpty() || attempt.result.stderr.isNotEmpty()) {
                    return attempt.result
                }
            }
            delay(pollMillis)
        }
    }

    private fun missingOutput(outcome: String) = TermuxRunResult(
        error = "termux_output_missing",
        detail = "$outcome: $outputPath",
        outputPath = outputPath,
    )
}

internal fun WorkspaceFailure.Reason.retryableTermuxRead() =
    this == WorkspaceFailure.Reason.MISSING || this == WorkspaceFailure.Reason.PROVIDER
