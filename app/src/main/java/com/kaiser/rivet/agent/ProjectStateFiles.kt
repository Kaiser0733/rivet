package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TextSnapshot
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath

/**
 * Reads project memory, legacy goals, and run journals through SAF.
 */
class ProjectStateFiles(private val workspace: SafWorkspace) {
    suspend fun loadLegacyGoal(): GoalDocument? = read(GoalDocument.PATH)?.let {
        try { GoalDocument.parse(it) } catch (_: Exception) { null }
    }

    suspend fun loadMemory(): String = read(MemoryFormat.PATH).orEmpty()

    suspend fun saveMemory(markdown: String) {
        write(MemoryFormat.PATH, if (markdown.isBlank()) "" else markdown)
    }

    suspend fun appendMemory(facts: List<String>): MemoryFormat.Append {
        val path = WorkspacePath.parse(MemoryFormat.PATH)
        val existing = snapshot(path)
        val result = MemoryFormat.append(existing?.text.orEmpty(), facts)
        if (result is MemoryFormat.Append.Saved) {
            ensureParents(path.parent())
            write(path, result.markdown, existing)
        }
        return result
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
        write(path, text, snapshot(path))
    }

    private suspend fun snapshot(path: WorkspacePath): TextSnapshot? = try {
        workspace.readTextFile(path)
    } catch (error: WorkspaceFailure) {
        if (error.reason == WorkspaceFailure.Reason.MISSING) null else throw error
    }

    private suspend fun write(path: WorkspacePath, text: String, existing: TextSnapshot?) {
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
