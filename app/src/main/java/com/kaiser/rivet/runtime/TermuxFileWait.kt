package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspaceFailure
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
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
) {
    suspend fun await(returned: Deferred<TermuxRunResult>, timeoutMillis: Long): TermuxRunResult? {
        var emptyBroadcast = false
        var lastFailure: WorkspaceFailure.Reason? = null
        val result = withTimeoutOrNull(timeoutMillis) {
            if (returned.isCompleted) {
                val broadcast = returned.await()
                if (broadcast.hasReply()) return@withTimeoutOrNull broadcast
            }
            coroutineScope {
                val files = async { awaitFiles { lastFailure = it } }
                try {
                    select<TermuxRunResult?> {
                        returned.onAwait { broadcast ->
                            if (broadcast.hasReply()) {
                                broadcast
                            } else {
                                emptyBroadcast = true
                                withTimeoutOrNull(FILE_SETTLE_MILLIS) { files.await() }
                            }
                        }
                        files.onAwait { it }
                    }
                } finally {
                    files.cancel()
                }
            }
        }
        return result ?: if (emptyBroadcast) {
            TermuxRunResult(
                error = "termux_output_missing",
                detail = "${lastFailure?.name ?: "MISSING"}: $outputPath",
                outputPath = outputPath,
            )
        } else null
    }

    private fun TermuxRunResult.hasReply() = error != null || stdout.isNotEmpty() || stderr.isNotEmpty()

    private suspend fun awaitFiles(onFailure: (WorkspaceFailure.Reason) -> Unit): TermuxRunResult {
        while (true) {
            when (val attempt = read()) {
                TermuxFileRead.Missing -> Unit
                is TermuxFileRead.Failed -> onFailure(attempt.reason)
                is TermuxFileRead.Done -> return attempt.result
            }
            delay(pollMillis)
        }
    }

    private companion object {
        const val FILE_SETTLE_MILLIS = 5_000L
    }
}
