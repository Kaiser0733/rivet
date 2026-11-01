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

// While a reasoning chain streams, the work that renders it starves the one-second
// ticker, so a counter on that line sits at 0s. Show the label alone there; the states
// that do tick (writing, a running tool, an approval prompt) keep their clock.
internal fun liveStatusShowsClock(toolRunning: Boolean, writing: Boolean,
                                  waitingForApproval: Boolean): Boolean =
    toolRunning || writing || waitingForApproval

internal fun liveStatusLine(label: String, elapsedSeconds: Long): String {
    val seconds = if (elapsedSeconds < 0) 0 else elapsedSeconds
    val clock = if (seconds < 60) "${seconds}s"
        else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    return "$label · $clock"
}

// A command holds the project lock until it exits, so Undo and retry cannot finish
// while one runs. Saying so is better than accepting a tap that does nothing.
internal fun commandBusyReason(commandRunning: Boolean): String? =
    if (commandRunning) "A command is running. Undo and retry work again when it finishes."
    else null
