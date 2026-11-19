package com.kaiser.rivet.ui.chat

/** Pure chip policy: label, tap target, and the title space the overlay reserves. */
internal fun subagentChipText(count: Int): String? {
    if (count <= 0) return null
    return if (count == 1) "1 subagent" else "$count subagents"
}

/** The chip's own tap opens the newest worker's sheet; the chevron lists them all. */
internal fun <T> chipSheetWorker(workers: List<T>): T? = workers.lastOrNull()

/**
 * The chip overlays the title's trailing edge instead of sitting in the icon row, so
 * the action icons keep their positions when workers appear or finish. The title
 * reserves this much end padding while the chip is visible.
 */
internal fun chatTitleEndPaddingDp(workerCount: Int): Int = if (workerCount > 0) 140 else 8
