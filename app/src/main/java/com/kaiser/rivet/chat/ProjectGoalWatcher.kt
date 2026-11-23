package com.kaiser.rivet.chat

internal class ProjectGoalWatcher {
    private data class Snapshot(val workspaceId: String, val markdown: String?)

    private var previous: Snapshot? = null

    fun remember(workspaceId: String?, markdown: String?) {
        previous = workspaceId?.let { Snapshot(it, markdown) }
    }

    fun observe(workspaceId: String?, markdown: String?): Boolean {
        val old = previous
        remember(workspaceId, markdown)
        return old != null && old.workspaceId == workspaceId && old.markdown != markdown
    }
}
