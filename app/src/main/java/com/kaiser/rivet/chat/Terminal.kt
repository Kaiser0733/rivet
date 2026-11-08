package com.kaiser.rivet.chat

import com.kaiser.rivet.runtime.RuntimeCommandResult

/** One user-terminal run. Owned by the view model so closing the panel keeps the run. */
data class TerminalUiState(
    val command: String = "",
    val output: String = "",
    val running: Boolean = false,
    val status: String? = null,
)

/** The terminal shares run_command's default wall clock (plan §5 rule 2). */
internal const val TERMINAL_TIMEOUT_MS = 300_000L

// Same control set the Processes registry strips; tab and newline stay readable.
private val TERMINAL_CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

/** The two captured streams as one transcript; capture bounds are applied upstream. */
internal fun terminalText(stdout: String, stderr: String): String {
    val combined = buildString {
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty() && !endsWith('\n')) append('\n')
            append(stderr)
        }
    }
    return combined.replace(TERMINAL_CONTROL, "\uFFFD")
}

/** The plain result line, naming the same flags the agent's run_command result carries. */
internal fun terminalStatus(result: RuntimeCommandResult?, cancelled: Boolean): String = when {
    cancelled -> "Stopped."
    result == null -> "Stopped."
    result.error == "workspace_unavailable" -> "No workspace is selected. Choose a project in the chat first."
    result.error == "process_limit" -> "Four processes are already running. Stop one and try again."
    result.error == "sync_required" -> "The workspace is syncing. Try again when it finishes."
    result.error != null -> "Could not run the command (${result.error})."
    result.timedOut -> "Stopped: timed out after 5 minutes."
    result.tooManyChildren -> "Stopped: too many child processes (limit 64)."
    result.exitCode == 0 -> "Finished."
    result.exitCode != null -> "Exit ${result.exitCode}."
    else -> "Finished."
}
