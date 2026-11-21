package com.kaiser.rivet.agent

/** Visible transcript markers for app-owned events; never sent to the model. */
internal object CompactionNotice {
    const val KIND = "compaction_notice"
    const val TEXT = "Conversation compacted"
    private const val LEGACY_TEXT = "Earlier turns were summarised to fit the model's window."

    fun completed(tokens: Long): String = "Compacted to $tokens tokens"

    fun display(text: String): String = if (text == LEGACY_TEXT) TEXT else text

    const val RESTORE_KIND = "restore_notice"
    const val RESTORE_TEXT = "Terminal restarted by restore — earlier scrollback was cleared."

    fun isNotice(kind: String?): Boolean = kind == KIND || kind == RESTORE_KIND
}

internal fun AgentMessage.isTranscriptEvent(): Boolean =
    if (role == AgentRole.Context) CompactionNotice.isNotice(validatedContext()?.kind)
    else internalContext == null
