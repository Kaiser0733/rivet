package com.kaiser.rivet.agent

/** Visible transcript markers for app-owned events; never sent to the model. */
internal object CompactionNotice {
    const val KIND = "compaction_notice"
    const val TEXT = "Earlier turns were summarised to fit the model's window."

    const val RESTORE_KIND = "restore_notice"
    const val RESTORE_TEXT = "Terminal restarted by restore — earlier scrollback was cleared."

    fun isNotice(kind: String?): Boolean = kind == KIND || kind == RESTORE_KIND
}
