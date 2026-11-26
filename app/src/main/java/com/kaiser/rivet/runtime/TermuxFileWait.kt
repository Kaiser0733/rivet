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
    val operationId: String? = null,
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
            while (true) {
                val failure = lastRead as? TermuxFileRead.Failed
                if (failure == null || failure.reason.retryableTermuxRead()) lastRead = read()
                val saved = lastRead as? TermuxFileRead.Done
                if (saved != null) return@withTimeoutOrNull saved.result
                if (returned.isCompleted) {
                    val callback = returned.await()
                    if (callback.stdout.isNotEmpty() || callback.stderr.isNotEmpty()) {
                        return@withTimeoutOrNull callback
                    }
                }
                delay(pollMillis)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        val outcome = when (val read = lastRead) {
            TermuxFileRead.Missing -> "MISSING"
            is TermuxFileRead.Failed -> read.reason.name
            is TermuxFileRead.Done -> "DONE exit=${read.result.exitCode} " +
                "stdout=${read.result.stdout.length} stderr=${read.result.stderr.length}"
        }
        onReadOutcome(outcome)
        val callbackError = if (returned.isCompleted) returned.await().takeIf { it.error != null } else null
        return result ?: callbackError ?: if (returned.isCompleted || lastRead != TermuxFileRead.Missing) {
            missingOutput(outcome)
        } else null
    }

    private fun missingOutput(outcome: String) = TermuxRunResult(
        error = "termux_output_missing",
        detail = "$outcome: $outputPath",
        outputPath = outputPath,
    )
}

internal fun WorkspaceFailure.Reason.retryableTermuxRead() =
    this == WorkspaceFailure.Reason.MISSING || this == WorkspaceFailure.Reason.PROVIDER
