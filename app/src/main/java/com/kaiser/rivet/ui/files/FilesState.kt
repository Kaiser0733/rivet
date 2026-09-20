package com.kaiser.rivet.ui.files

import com.kaiser.rivet.workspace.SearchReport
import com.kaiser.rivet.workspace.TextSnapshot
import com.kaiser.rivet.workspace.WorkspaceEntry
import com.kaiser.rivet.workspace.WorkspacePath

data class FilesState(
    val selected: Boolean = false,
    val directory: WorkspacePath = WorkspacePath.ROOT,
    val folder: WorkspaceEntry? = null,
    val entries: List<WorkspaceEntry> = emptyList(),
    val opened: WorkspaceEntry? = null,
    val snapshot: TextSnapshot? = null,
    val draft: String = "",
    val fileNotice: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val mutating: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val search: SearchReport? = null,
) {
    val dirty: Boolean get() = snapshot != null && draft != snapshot.text
}
