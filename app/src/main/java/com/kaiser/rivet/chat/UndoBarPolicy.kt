package com.kaiser.rivet.chat

import com.kaiser.rivet.runtime.CheckpointRecord

/** A bar belongs to the session and checkpoint that produced it, until dismissed. */
internal fun undoBarVisible(record: CheckpointRecord?, currentSessionId: String?): Boolean =
    record != null && record.sessionId != null && record.sessionId == currentSessionId &&
        !record.barDismissed && !record.undone && !record.desynced
