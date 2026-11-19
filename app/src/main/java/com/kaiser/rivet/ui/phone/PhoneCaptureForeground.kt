package com.kaiser.rivet.ui.phone

/** How long a capture waits for Rivet to come back to the foreground before giving up. */
internal const val PHONE_SHOT_FOREGROUND_WAIT_MS = 60_000L

internal const val PHONE_SHOT_WAITING_FOREGROUND = "phone_shot_waiting_foreground"
internal const val PHONE_SHOT_NEEDS_FOREGROUND = "phone_shot_needs_foreground"

/**
 * The consent launcher only works while the activity is resumed. After a refusal this
 * decides the outcome: a launch accepted while resumed proceeds, a refusal while resumed
 * is a genuine unavailable, and a bounded wait that expires needs the foreground.
 * `null` means keep waiting.
 */
internal fun phoneShotFailureCode(
    launchAccepted: Boolean,
    refusedWhileResumed: Boolean,
    timedOut: Boolean,
): String? = when {
    launchAccepted -> null
    refusedWhileResumed -> "phone_shot_unavailable"
    timedOut -> PHONE_SHOT_NEEDS_FOREGROUND
    else -> null
}
