package com.kaiser.rivet.chat

// What the composer sends for a given draft. The send button and the keyboard action
// key both go through this, so they cannot disagree about trimming or emptiness.
internal fun draftToSend(draft: String, canSend: Boolean): String? =
    if (!canSend) null else draft.trim().ifEmpty { null }
