package com.kaiser.rivet.chat

// What the composer sends for a given draft. The send button and the keyboard action
// key both go through this, so they cannot disagree about trimming or emptiness.
internal fun draftToSend(draft: String, canSend: Boolean): String? =
    if (!canSend) null else draft.trim().ifEmpty { null }

// Which conversation a draft belongs to. The composer keeps one entry per conversation so
// text typed in one chat is never offered to another and is still there on the way back.
// A `rememberSaveable(sessionId)` on the text itself cannot do that: its documented contract
// is to revert to the init value when its inputs change, so a switch would drop the draft
// instead of keeping it, and switching back would show an empty composer.
internal fun draftFor(drafts: Map<String, String>, sessionId: String?): String =
    drafts[sessionId.orEmpty()].orEmpty()

internal fun withDraft(drafts: Map<String, String>, sessionId: String?, text: String): Map<String, String> {
    val key = sessionId.orEmpty()
    // An empty draft is not kept: the map holds only conversations with something typed in.
    return if (text.isEmpty()) drafts - key else drafts + (key to text)
}
