package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TextEdit
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath

class SafAgentWorkspace(private val workspace: SafWorkspace) : AgentWorkspace {
    override suspend fun stat(path: String): AgentWorkspaceEntry = call {
        workspace.stat(WorkspacePath.parse(path)).let {
            AgentWorkspaceEntry(it.path.value, it.directory, it.size)
        }
    }
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

    override suspend fun createFile(path: String): AgentCreatedFile = call {
        val created = workspace.createFile(WorkspacePath.parse(path))
        try {
            workspace.readCreatedFile(created.path).let {
                AgentCreatedFile(it.path.value, it.sha256, it.size)
            }
        } catch (e: WorkspaceFailure) {
            // Creation is already committed. Report its confirmed identity even
            // when this provider refuses the follow-up inspection.
            AgentCreatedFile(created.path.value, inspectionError = e.reason.name.lowercase())
        }
    }

    override suspend fun createDirectory(path: String): AgentWorkspaceEntry = call {
        workspace.createDirectory(WorkspacePath.parse(path)).let {
            AgentWorkspaceEntry(it.path.value, it.directory, it.size)
        }
    }

    override suspend fun rename(path: String, newName: String): AgentWorkspaceEntry = call {
        workspace.rename(WorkspacePath.parse(path), newName).let {
            AgentWorkspaceEntry(it.path.value, it.directory, it.size)
        }
    }

    override suspend fun move(path: String, destination: String): AgentWorkspaceEntry = call {
        workspace.move(WorkspacePath.parse(path), WorkspacePath.parse(destination)).let {
            AgentWorkspaceEntry(it.path.value, it.directory, it.size)
        }
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
