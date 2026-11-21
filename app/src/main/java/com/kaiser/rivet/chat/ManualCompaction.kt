package com.kaiser.rivet.chat

/** What a tap on Compact does right now. */
internal enum class CompactTrigger { Fire, RunActive, NothingToCompact }

/**
 * A running reply owns the transcript and already compacts inside its turn, so a manual
 * tap waits for it. A session with no visible messages has nothing to summarise.
 */
internal fun compactTrigger(streaming: Boolean, hasMessages: Boolean): CompactTrigger = when {
    streaming -> CompactTrigger.RunActive
    !hasMessages -> CompactTrigger.NothingToCompact
    else -> CompactTrigger.Fire
}

/** The row only sees the run: an empty session still taps through to the plain no-op. */
internal fun compactRowEnabled(streaming: Boolean): Boolean =
    compactTrigger(streaming, hasMessages = true) != CompactTrigger.RunActive
