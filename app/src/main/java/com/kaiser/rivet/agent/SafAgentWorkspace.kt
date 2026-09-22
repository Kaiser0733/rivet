package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TextEdit
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath

class SafAgentWorkspace(private val workspace: SafWorkspace) : AgentWorkspace {
    override suspend fun list(path: String): List<AgentWorkspaceEntry> = call {
        workspace.listDirectory(WorkspacePath.parse(path)).map {
            AgentWorkspaceEntry(it.path.value, it.directory, it.size)
        }
    }

    override suspend fun read(path: String): AgentFileSnapshot = call {
        workspace.readTextFile(WorkspacePath.parse(path)).let {
            AgentFileSnapshot(it.path.value, it.text, it.sha256, it.size)
        }
    }

    override suspend fun search(path: String, query: String): AgentSearchReport = call {
        workspace.search(query, WorkspacePath.parse(path)).let { report ->
            AgentSearchReport(
                report.hits.map { AgentSearchHit(it.path.value, it.line, it.context) },
                report.limited,
                report.filesScanned,
                report.entriesVisited,
                report.bytesScanned,
                report.skipped,
            )
        }
    }

    override suspend fun write(path: String, content: String, expectedHash: String): AgentFileSnapshot = call {
        workspace.writeTextFile(WorkspacePath.parse(path), content, expectedHash).let {
            AgentFileSnapshot(it.path.value, it.text, it.sha256, it.size)
        }
    }

    override suspend fun patch(path: String, expectedHash: String, edits: List<AgentTextEdit>): AgentFileSnapshot = call {
        workspace.applyTextPatch(
            WorkspacePath.parse(path), expectedHash, edits.map { TextEdit(it.oldText, it.newText) },
        ).let { AgentFileSnapshot(it.path.value, it.text, it.sha256, it.size) }
    }

    override suspend fun createFile(path: String): AgentFileSnapshot = call {
        val created = workspace.createFile(WorkspacePath.parse(path))
        workspace.readTextFile(created.path).let {
            AgentFileSnapshot(it.path.value, it.text, it.sha256, it.size)
        }
    }

    override suspend fun createDirectory(path: String) = call<Unit> {
        workspace.createDirectory(WorkspacePath.parse(path)); Unit
    }

    override suspend fun rename(path: String, newName: String) = call<Unit> {
        workspace.rename(WorkspacePath.parse(path), newName); Unit
    }

    override suspend fun move(path: String, destination: String) = call<Unit> {
        workspace.move(WorkspacePath.parse(path), WorkspacePath.parse(destination)); Unit
    }

    override suspend fun delete(path: String) = call<Unit> {
        workspace.delete(WorkspacePath.parse(path)); Unit
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: WorkspaceFailure) {
        throw AgentWorkspaceFailure(e.reason.name.lowercase())
    }
}
