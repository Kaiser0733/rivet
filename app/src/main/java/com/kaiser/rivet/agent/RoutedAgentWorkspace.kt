package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.GrantPath
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import com.kaiser.rivet.workspace.WorkspaceStructuralStamp
import com.kaiser.rivet.workspace.WorkspaceText
import com.kaiser.rivet.workspace.readWorkspaceTextRegion
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class RoutedAgentWorkspace(
    private val project: AgentWorkspace?,
    private val grant: suspend (id: String, write: Boolean) -> AgentWorkspace,
    private val inbox: File?,
) : AgentWorkspace {
    suspend fun checkWrite(raw: String) {
        val path = GrantPath.parse(raw)
        if (path.prefix != null) target(path, write = true)
    }

    private suspend fun target(path: GrantPath, write: Boolean): AgentWorkspace = when {
        path.readOnly -> throw AgentWorkspaceFailure(if (write) "inbox_read_only" else "inbox_search_unavailable")
        path.grantId != null -> grant(path.grantId, write)
        else -> project ?: throw AgentWorkspaceFailure("file_project_unavailable")
    }

    private suspend fun <T> route(raw: String, write: Boolean = false, operation: suspend (AgentWorkspace, GrantPath) -> T): T {
        val path = GrantPath.parse(raw)
        val workspace = target(path, write)
        return try { operation(workspace, path) }
        catch (e: AgentWorkspaceFailure) {
            if (path.grantId != null && e.code == "permission") {
                // Recheck live access to distinguish revocation from provider-specific errors.
                target(path, write)
            }
            throw e
        }
    }

    private fun inboxFile(path: WorkspacePath): File {
        val directory = inbox?.canonicalFile ?: throw AgentWorkspaceFailure("missing")
        if (path.isRoot) return directory
        val file = File(directory, path.value).canonicalFile
        if (file.parentFile != directory || !file.isFile) throw AgentWorkspaceFailure("missing")
        return file
    }

    override suspend fun stat(path: String): AgentWorkspaceEntry =
        if (GrantPath.parse(path).readOnly) withContext(Dispatchers.IO) {
            val file = inboxFile(GrantPath.parse(path).path)
            AgentWorkspaceEntry(path, file == inbox?.canonicalFile, file.length())
        } else route(path) { ws, route -> ws.stat(route.path.value).let { it.copy(path = route.qualify(it.path)) } }

    override suspend fun list(path: String): List<AgentWorkspaceEntry> =
        if (GrantPath.parse(path).readOnly) withContext(Dispatchers.IO) {
            val folder = inboxFile(GrantPath.parse(path).path)
            if (folder.isFile) throw AgentWorkspaceFailure("not_directory")
            folder.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == folder }
                .sortedBy { it.name }.map { AgentWorkspaceEntry("inbox/${it.name}", false, it.length()) }
        } else route(path) { ws, route ->
            ws.list(route.path.value).map { it.copy(path = route.qualify(it.path)) }
        }

    override suspend fun read(path: String): AgentFileSnapshot =
        if (GrantPath.parse(path).readOnly) withContext(Dispatchers.IO) {
            val file = inboxFile(GrantPath.parse(path).path)
            if (!file.isFile) throw AgentWorkspaceFailure("not_file")
            try {
                val bytes = file.inputStream().use { WorkspaceText.readBounded(it) { ensureActive() } }
                AgentFileSnapshot(path, WorkspaceText.decode(bytes), WorkspaceText.sha256(bytes), bytes.size.toLong())
            } catch (e: WorkspaceFailure) { throw AgentWorkspaceFailure(e.reason.name.lowercase()) }
        } else route(path) { ws, route -> ws.read(route.path.value).let { it.copy(path = route.qualify(it.path)) } }

    override suspend fun readRegion(path: String, offset: Long, maxBytes: Int): AgentFileRegion =
        if (GrantPath.parse(path).readOnly) withContext(Dispatchers.IO) {
            val file = inboxFile(GrantPath.parse(path).path)
            if (!file.isFile) throw AgentWorkspaceFailure("not_file")
            try {
                val region = file.inputStream().use { readWorkspaceTextRegion(it, offset, maxBytes) { ensureActive() } }
                AgentFileRegion(path, region.text, region.sha256, region.size, region.start, region.end)
            } catch (e: WorkspaceFailure) { throw AgentWorkspaceFailure(e.reason.name.lowercase()) }
        } else route(path) { ws, route -> ws.readRegion(route.path.value, offset, maxBytes).copy(path = path) }

    override suspend fun search(path: String, query: String): AgentSearchReport = route(path) { ws, route ->
        ws.search(route.path.value, query).let { report ->
            report.copy(hits = report.hits.map { it.copy(path = route.qualify(it.path)) })
        }
    }

    override suspend fun observeStructural(path: String, recursive: Boolean) = route(path, write = true) { ws, route ->
        ws.observeStructural(route.path.value, recursive)
    }

    override suspend fun write(path: String, content: String, expectedHash: String) = route(path, write = true) { ws, route ->
        ws.write(route.path.value, content, expectedHash).let { it.copy(path = route.qualify(it.path)) }
    }

    override suspend fun writeDownloaded(path: String, input: InputStream, expectedExistingHash: String?) =
        route(path, write = true) { ws, route ->
            ws.writeDownloaded(route.path.value, input, expectedExistingHash).let { it.copy(path = route.qualify(it.path)) }
        }

    override suspend fun patch(path: String, expectedHash: String, edits: List<AgentTextEdit>) =
        route(path, write = true) { ws, route ->
            ws.patch(route.path.value, expectedHash, edits).let { it.copy(path = route.qualify(it.path)) }
        }

    override suspend fun createFile(path: String) = route(path, write = true) { ws, route ->
        ws.createFile(route.path.value).let { it.copy(path = route.qualify(it.path)) }
    }

    override suspend fun createFileWithParents(path: String) = route(path, write = true) { ws, route ->
        ws.createFileWithParents(route.path.value).let { it.copy(path = route.qualify(it.path)) }
    }

    override suspend fun createDirectory(path: String) = route(path, write = true) { ws, route ->
        ws.createDirectory(route.path.value).let { it.copy(path = route.qualify(it.path)) }
    }

    override suspend fun rename(path: String, newName: String, approved: WorkspaceStructuralStamp) =
        route(path, write = true) { ws, route ->
            ws.rename(route.path.value, newName, approved).let { it.copy(path = route.qualify(it.path)) }
        }

    override suspend fun move(path: String, destination: String, approvedSource: WorkspaceStructuralStamp,
                              approvedDestination: WorkspaceStructuralStamp) =
        route(path, write = true) { ws, source ->
            val target = GrantPath.parse(destination)
            if (source.prefix != target.prefix) throw AgentWorkspaceFailure("cross_folder_move")
            ws.move(source.path.value, target.path.value, approvedSource, approvedDestination)
                .let { it.copy(path = source.qualify(it.path)) }
        }

    override suspend fun delete(path: String, approved: WorkspaceStructuralStamp) =
        route(path, write = true) { ws, route -> ws.delete(route.path.value, approved) }
}
