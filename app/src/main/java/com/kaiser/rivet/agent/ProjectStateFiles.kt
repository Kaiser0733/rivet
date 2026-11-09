package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath

/**
 * The two files Rivet owns under `.rivet/`, plus run journals. Every read goes to SAF:
 * external edits are the point, so there is no stale in-memory copy.
 */
class ProjectStateFiles(private val workspace: SafWorkspace) {
    suspend fun loadGoal(): GoalDocument? = read(GoalDocument.PATH)?.let {
        try { GoalDocument.parse(it) } catch (_: Exception) { null }
    }

    suspend fun saveGoal(goal: GoalDocument) = write(GoalDocument.PATH, GoalDocument.render(goal))

    suspend fun loadMemory(): String = read(MemoryFormat.PATH).orEmpty()

    suspend fun saveMemory(markdown: String) {
        write(MemoryFormat.PATH, if (markdown.isBlank()) "" else markdown)
    }

    suspend fun read(relative: String): String? {
        if (relative.isEmpty()) return null
        return try {
            workspace.readTextFile(WorkspacePath.parse(relative)).text
        } catch (error: WorkspaceFailure) {
            if (error.reason == WorkspaceFailure.Reason.MISSING) null else throw error
        }
    }

    suspend fun write(relative: String, text: String) {
        val path = WorkspacePath.parse(relative)
        ensureParents(path.parent())
        val existing = try {
            workspace.readTextFile(path)
        } catch (error: WorkspaceFailure) {
            if (error.reason != WorkspaceFailure.Reason.MISSING) throw error
            null
        }
        if (existing == null) {
            val created = workspace.createFile(path)
            // A provider may normalize the name; never write through an unexpected path.
            if (created.path != path) {
                throw WorkspaceFailure(WorkspaceFailure.Reason.CONFLICT)
            }
            val empty = workspace.readTextFile(created.path)
            workspace.writeTextFile(created.path, text, empty.sha256)
        } else {
            workspace.writeTextFile(path, text, existing.sha256)
        }
    }

    suspend fun listDirectories(relative: String): List<String> {
        val path = WorkspacePath.parse(relative)
        return try {
            workspace.listDirectory(path).filter { it.directory }.map { it.path.value }
        } catch (error: WorkspaceFailure) {
            if (error.reason == WorkspaceFailure.Reason.MISSING) emptyList() else throw error
        }
    }

    suspend fun ensureDirectory(relative: String) {
        if (relative.isEmpty()) return
        ensureParents(WorkspacePath.parse(relative))
    }

    private suspend fun ensureParents(path: WorkspacePath) {
        var current = WorkspacePath.ROOT
        for (segment in path.segments) {
            current = current.child(segment)
            try {
                val entry = workspace.stat(current)
                if (!entry.directory) throw WorkspaceFailure(WorkspaceFailure.Reason.NOT_DIRECTORY)
            } catch (error: WorkspaceFailure) {
                if (error.reason != WorkspaceFailure.Reason.MISSING) throw error
                workspace.createDirectory(current)
            }
        }
    }
}
