package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TextEdit
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import com.kaiser.rivet.workspace.WorkspaceStructuralStamp
import java.io.InputStream

class SafAgentWorkspace(private val workspace: SafWorkspace) : AgentWorkspace {
    override suspend fun observeStructural(path: String, recursive: Boolean): WorkspaceStructuralStamp = call {
        workspace.observeStructural(WorkspacePath.parse(path), recursive)
    }
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

    override suspend fun writeDownloaded(
        path: String,
        input: InputStream,
        expectedExistingHash: String?,
    ): AgentFileSnapshot = call {
        val requested = WorkspacePath.parse(path)
        val existing = try { workspace.stat(requested) }
            catch (failure: WorkspaceFailure) {
                if (failure.reason == WorkspaceFailure.Reason.MISSING) null else throw failure
            }
        if (existing != null) {
            if (existing.directory) throw WorkspaceFailure(WorkspaceFailure.Reason.NOT_FILE)
            val expected = expectedExistingHash ?: throw AgentWorkspaceFailure("destination_exists")
            val saved = workspace.writeFileFrom(requested, input, expected, existing.documentId)
            AgentFileSnapshot(requested.value, text = "", sha256 = saved.sha256, size = saved.size)
        } else {
            if (expectedExistingHash != null) throw WorkspaceFailure(WorkspaceFailure.Reason.MISSING)
            val created = workspace.createFile(requested)
            val actualPath = created.path
            val empty = workspace.fingerprint(actualPath)
            if (empty.size != 0L || empty.sha256 != EMPTY_FILE_SHA256) {
                throw WorkspaceFailure(WorkspaceFailure.Reason.CONFLICT)
            }
            val confirmed = workspace.stat(actualPath)
            val saved = workspace.writeFileFrom(actualPath, input, empty.sha256, confirmed.documentId)
            AgentFileSnapshot(actualPath.value, "", saved.sha256, saved.size)
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

    override suspend fun rename(path: String, newName: String, approved: WorkspaceStructuralStamp): AgentWorkspaceEntry = call {
        workspace.rename(WorkspacePath.parse(path), newName, approved).let {
            AgentWorkspaceEntry(it.path.value, it.directory, it.size)
        }
    }

    override suspend fun move(path: String, destination: String, approvedSource: WorkspaceStructuralStamp,
                              approvedDestination: WorkspaceStructuralStamp): AgentWorkspaceEntry = call {
        workspace.move(WorkspacePath.parse(path), WorkspacePath.parse(destination), approvedSource,
            approvedDestination).let {
            AgentWorkspaceEntry(it.path.value, it.directory, it.size)
        }
    }

    override suspend fun delete(path: String, approved: WorkspaceStructuralStamp) = call<Unit> {
        workspace.delete(WorkspacePath.parse(path), approved); Unit
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: WorkspaceFailure) {
        throw AgentWorkspaceFailure(e.reason.name.lowercase())
    }

    private companion object {
        const val EMPTY_FILE_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
