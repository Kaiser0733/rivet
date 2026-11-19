package com.kaiser.rivet.agent

/** The visible transcript marker for a completed compaction; never sent to the model. */
internal object CompactionNotice {
    const val KIND = "compaction_notice"
    const val TEXT = "Earlier turns were summarised to fit the model's window."

    fun isNotice(kind: String?): Boolean = kind == KIND
}
