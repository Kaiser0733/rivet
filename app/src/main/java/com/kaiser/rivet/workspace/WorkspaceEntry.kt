package com.kaiser.rivet.workspace

import java.util.Locale

data class WorkspaceCapabilities(
    val create: Boolean = false,
    val write: Boolean = false,
    val delete: Boolean = false,
    val rename: Boolean = false,
    val move: Boolean = false,
)

data class WorkspaceEntry(
    val path: WorkspacePath,
    val documentId: String,
    val directory: Boolean,
    val mimeType: String,
    val size: Long? = null,
    val modifiedTime: Long? = null,
    val capabilities: WorkspaceCapabilities = WorkspaceCapabilities(),
) {
    companion object {
        val ORDER = compareBy<WorkspaceEntry> { !it.directory }
            .thenBy { it.path.name.lowercase(Locale.ROOT) }
            .thenBy { it.path.name }.thenBy { it.documentId }
    }
}

data class WorkspaceStructuralStamp(
    val documentId: String,
    val parentDocumentId: String,
    val directory: Boolean,
    val state: String?,
    val recursive: Boolean,
)

// Confined to the ViewModel's main-thread state transitions.
class WorkspaceEpoch {
    private var revision = 0L
    fun next(): Long = ++revision
    fun isCurrent(ticket: Long): Boolean = ticket == revision
}
