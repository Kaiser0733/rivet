package com.kaiser.rivet.chat

// Wording and seconds formatting for the live turn status. Kept free of Compose and
// Android types so the rules can be checked without building the UI.

internal fun liveStatusLabel(activity: String?, toolRunning: Boolean,
                             writing: Boolean, waitingForApproval: Boolean): String = when {
    waitingForApproval -> "Waiting for approval"
    // While a tool runs the activity text already names what it is doing.
    toolRunning -> activity?.trim()?.trimEnd('…', '.')?.trim()?.ifBlank { null } ?: "Working"
    writing -> "Writing"
    // Nothing has arrived yet: the model is reading the turn and its context.
    else -> "Thinking"
}

internal fun liveStatusLine(label: String, elapsedSeconds: Long): String {
    val seconds = if (elapsedSeconds < 0) 0 else elapsedSeconds
    val clock = if (seconds < 60) "${seconds}s"
        else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    return "$label · $clock"
}
