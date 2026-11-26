package com.kaiser.rivet.workspace

import com.kaiser.rivet.agent.AgentWorkspaceFailure
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ExternalDeleteFailure(val code: String) : Exception(code)

/**
 * What the delete approval can honestly promise for a path outside the project.
 * A path inside the project is covered by the turn checkpoint, not by this store.
 */
sealed interface ExternalDeleteProtection {
    data class Available(val files: Int, val bytes: Long) : ExternalDeleteProtection
    data class TooLarge(val files: Int, val bytes: Long, val limitBytes: Long) : ExternalDeleteProtection
    data object Unreadable : ExternalDeleteProtection
}

data class ExternalDeleteStaged(val id: String)

@Serializable
data class ExternalDeleteFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class ExternalDeleteEntry(
    val id: String,
    val grantId: String,
    val path: String,
    val directory: Boolean,
    val createdAt: Long,
    val sessionId: String? = null,
    val turnId: String? = null,
    val dismissed: Boolean = false,
    val files: List<ExternalDeleteFile> = emptyList(),
    val directories: List<String> = emptyList(),
) {
    val displayPath: String get() = "grants/$grantId/$path"
    val groupKey: String get() = turnId ?: id
    val bytes: Long get() = files.sumOf { it.size }
}

/** One Undo row: every external delete Rivet staged during the same turn. */
data class ExternalDeleteUndo(
    val id: String,
    val createdAt: Long,
    val paths: List<String>,
    val fileCount: Int,
    val bytes: Long,
)

data class ExternalDeleteRestore(val paths: List<String>, val fileCount: Int)

/**
 * Stages an external-grant delete inside app storage before the provider removes it,
 * so Undo can put the bytes back. The store is deliberately separate from the turn
 * checkpoint: the checkpoint is tied to a project mirror, while a grant delete can
 * happen with no project open.
 */
interface ExternalDeleteGuard {
    suspend fun plan(path: String): ExternalDeleteProtection?
    suspend fun stage(path: String, sessionId: String?, turnId: String?): ExternalDeleteStaged?
    suspend fun discard(staged: ExternalDeleteStaged)
}

class ExternalDeleteStore(
    private val storage: File,
    private val resolveGrant: suspend (id: String) -> SafWorkspace,
    private val maxBytes: Long = MAX_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val retainedGroups: Int = RETAINED_GROUPS,
) : ExternalDeleteGuard {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun plan(path: String): ExternalDeleteProtection? = withContext(Dispatchers.IO) {
        val route = try { grantRoute(path) } catch (_: WorkspaceFailure) { null } ?: return@withContext null
        try {
            resolveGrant(route.grantId).let { workspace ->
                measure(workspace, route.path, workspace.stat(route.path))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ExternalDeleteProtection.Unreadable
        }
    }

    override suspend fun stage(path: String, sessionId: String?, turnId: String?): ExternalDeleteStaged? =
        withContext(Dispatchers.IO) {
            val route = grantRoute(path) ?: return@withContext null
            lock.withLock {
                val workspace = try {
                    resolveGrant(route.grantId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AgentWorkspaceFailure) {
                    throw ExternalDeleteFailure(e.code)
                }
                val root = try {
                    workspace.stat(route.path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WorkspaceFailure) {
                    throw ExternalDeleteFailure(e.reason.name.lowercase())
                }
                val id = UUID.randomUUID().toString()
                ensureStorage()
                val temp = File(storage, ".$id.tmp")
                if (!temp.mkdirs()) throw ExternalDeleteFailure("storage")
                try {
                    val files = mutableListOf<ExternalDeleteFile>()
                    val directories = mutableListOf<String>()
                    var bytes = 0L
                    if (root.directory) {
                        directories += route.path.value
                        val pending = java.util.ArrayDeque<WorkspacePath>()
                        pending.add(route.path)
                        while (pending.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            val directory = pending.removeFirst()
                            for (child in workspace.listDirectory(directory)) {
                                if (child.directory) {
                                    directories += child.path.value
                                    pending.add(child.path)
                                } else {
                                    if (files.size >= maxEntries) throw ExternalDeleteFailure("limit")
                                    val remaining = maxBytes - bytes
                                    if (remaining <= 0) throw ExternalDeleteFailure("too_large")
                                    val copy = copyInto(workspace, child.path, payloadFile(temp, child.path.value), remaining)
                                    bytes += copy.size
                                    files += ExternalDeleteFile(child.path.value, copy.size, copy.sha256)
                                }
                            }
                        }
                    } else {
                        root.size?.let { if (it > maxBytes) throw ExternalDeleteFailure("too_large") }
                        val copy = copyInto(workspace, route.path, payloadFile(temp, route.path.value), maxBytes)
                        bytes = copy.size
                        files += ExternalDeleteFile(route.path.value, copy.size, copy.sha256)
                    }
                    val entry = ExternalDeleteEntry(
                        id = id,
                        grantId = route.grantId,
                        path = route.path.value,
                        directory = root.directory,
                        createdAt = System.currentTimeMillis(),
                        sessionId = sessionId,
                        turnId = turnId,
                        files = files,
                        directories = directories,
                    )
                    writeEntry(temp, entry)
                    Files.move(temp.toPath(), folder(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
                    runCatching { prune(entry.groupKey) }
                    ExternalDeleteStaged(id)
                } catch (e: CancellationException) {
                    removeTree(temp)
                    throw e
                } catch (e: Exception) {
                    removeTree(temp)
                    throw e
                }
            }
        }

    override suspend fun discard(staged: ExternalDeleteStaged) = withContext(Dispatchers.IO) {
        lock.withLock { removeTree(folder(staged.id)) }
    }

    suspend fun latest(sessionId: String?, since: Long = 0L): ExternalDeleteUndo? = withContext(Dispatchers.IO) {
        val entries = entries().filter { !it.dismissed && it.sessionId == sessionId && it.createdAt >= since }
        val newest = entries.maxByOrNull { it.createdAt } ?: return@withContext null
        val group = entries.filter { it.groupKey == newest.groupKey }
        ExternalDeleteUndo(
            id = newest.groupKey,
            createdAt = group.maxOf { it.createdAt },
            paths = group.map { it.displayPath }.distinct(),
            fileCount = group.sumOf { it.files.size.coerceAtLeast(1) },
            bytes = group.sumOf { it.bytes },
        )
    }

    suspend fun undo(groupId: String): ExternalDeleteRestore = withContext(Dispatchers.IO) {
        lock.withLock {
            val group = entries().filter { it.groupKey == groupId }.sortedByDescending { it.createdAt }
            if (group.isEmpty()) throw ExternalDeleteFailure("undo_unavailable")
            val restored = mutableListOf<String>()
            var files = 0
            for (entry in group) {
                withContext(NonCancellable) { restore(entry) }
                removeTree(folder(entry.id))
                restored += entry.displayPath
                files += entry.files.size
            }
            ExternalDeleteRestore(restored, files)
        }
    }

    suspend fun dismiss(groupId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            entries().filter { it.groupKey == groupId }.forEach { removeTree(folder(it.id)) }
        }
    }

    private suspend fun measure(
        workspace: SafWorkspace,
        path: WorkspacePath,
        root: WorkspaceEntry,
    ): ExternalDeleteProtection {
        if (!root.directory) {
            workspace.canRead(path)
            val bytes = root.size ?: workspace.fingerprint(path).size
            return if (bytes > maxBytes) ExternalDeleteProtection.TooLarge(1, bytes, maxBytes)
            else ExternalDeleteProtection.Available(1, bytes)
        }
        var files = 0
        var bytes = 0L
        val pending = java.util.ArrayDeque<WorkspacePath>()
        pending.add(path)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            for (child in workspace.listDirectory(pending.removeFirst())) {
                if (child.directory) {
                    pending.add(child.path)
                } else {
                    workspace.canRead(child.path)
                    files++
                    bytes += child.size ?: workspace.fingerprint(child.path).size
                    if (files > maxEntries || bytes > maxBytes) {
                        return ExternalDeleteProtection.TooLarge(files, bytes, maxBytes)
                    }
                }
            }
        }
        return ExternalDeleteProtection.Available(files, bytes)
    }

    private suspend fun copyInto(
        workspace: SafWorkspace,
        path: WorkspacePath,
        destination: File,
        limit: Long,
    ): SafWorkspace.BinaryFingerprint {
        destination.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw ExternalDeleteFailure("storage") }
        return try {
            FileOutputStream(destination).use { output ->
                workspace.copyFileTo(path, output, limit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WorkspaceFailure) {
            throw ExternalDeleteFailure(
                if (e.reason == WorkspaceFailure.Reason.LIMIT) "too_large" else e.reason.name.lowercase(),
            )
        } catch (_: Exception) {
            throw ExternalDeleteFailure("unreadable")
        }
    }

    private suspend fun restore(entry: ExternalDeleteEntry) {
        val workspace = try {
            resolveGrant(entry.grantId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AgentWorkspaceFailure) {
            throw ExternalDeleteFailure(e.code)
        }
        val target = try { workspace.stat(WorkspacePath.parse(entry.path)) } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (e: WorkspaceFailure) {
            if (e.reason != WorkspaceFailure.Reason.MISSING) {
                throw ExternalDeleteFailure(e.reason.name.lowercase())
            }
            null
        }
        if (target != null) throw ExternalDeleteFailure("undo_conflict")

        entry.directories.sortedBy { it.count { part -> part == '/' } }.forEach { relative ->
            ensureDirectory(workspace, WorkspacePath.parse(relative))
        }
        for (file in entry.files) {
            val relative = safeRelative(file.path)
            val created = try {
                workspace.createFileWithParents(WorkspacePath.parse(relative))
            } catch (e: CancellationException) {
                throw e
            } catch (e: WorkspaceFailure) {
                throw ExternalDeleteFailure(e.reason.name.lowercase())
            }
            val actual = created.path
            val restored = try {
                val staged = File(payloadRoot(folder(entry.id)), relative)
                if (!staged.isFile) throw ExternalDeleteFailure("undo_unavailable")
                val document = workspace.stat(actual)
                FileInputStream(staged).use { input ->
                    workspace.writeFileFrom(actual, input, EMPTY_SHA256, document.documentId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WorkspaceFailure) {
                throw ExternalDeleteFailure(e.reason.name.lowercase())
            } catch (e: ExternalDeleteFailure) {
                throw e
            } catch (_: Exception) {
                throw ExternalDeleteFailure("undo_failed")
            }
            if (restored.size != file.size || restored.sha256 != file.sha256) {
                throw ExternalDeleteFailure("undo_failed")
            }
        }
    }

    private suspend fun ensureDirectory(workspace: SafWorkspace, path: WorkspacePath) {
        if (path.isRoot) return
        val existing = try {
            workspace.stat(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WorkspaceFailure) {
            if (e.reason != WorkspaceFailure.Reason.MISSING) {
                throw ExternalDeleteFailure(e.reason.name.lowercase())
            }
            null
        }
        when {
            existing?.directory == true -> return
            existing != null -> throw ExternalDeleteFailure("undo_conflict")
        }
        ensureDirectory(workspace, path.parent())
        try {
            workspace.createDirectory(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WorkspaceFailure) {
            throw ExternalDeleteFailure(e.reason.name.lowercase())
        }
    }

    private fun entries(): List<ExternalDeleteEntry> = storage.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { folder ->
            try {
                val entry = json.decodeFromString(ExternalDeleteEntry.serializer(), File(folder, "entry.json").readText())
                if (entry.id != folder.name) null else entry
            } catch (_: Exception) {
                null
            }
        }

    private fun prune(keep: String) {
        val all = entries()
        val groups = all.groupBy { it.groupKey }
            .mapValues { (_, value) -> value.maxOf { it.createdAt } }
            .entries.sortedByDescending { it.value }
        var retained = 0
        var bytes = 0L
        for ((key, _) in groups) {
            val groupBytes = all.filter { it.groupKey == key }.sumOf { it.bytes }
            val allowed = key == keep || (retained < retainedGroups && bytes + groupBytes <= maxTotalBytes)
            if (allowed) {
                retained++
                bytes += groupBytes
            } else {
                all.filter { it.groupKey == key }.forEach { removeTree(folder(it.id)) }
            }
        }
    }

    private fun grantRoute(path: String): Route? {
        val parsed = GrantPath.parse(path)
        if (parsed.readOnly || parsed.grantId == null || parsed.path.isRoot) return null
        return Route(parsed.grantId, parsed.path)
    }

    private fun folder(id: String): File {
        if (!Regex("[0-9a-fA-F-]{36}").matches(id)) throw ExternalDeleteFailure("invalid_entry")
        return File(storage, id)
    }

    private fun ensureStorage() {
        if (!storage.isDirectory && !storage.mkdirs()) throw ExternalDeleteFailure("storage")
    }

    private fun payloadRoot(folder: File): File = File(folder, "payload")

    private fun payloadFile(folder: File, relative: String): File =
        File(payloadRoot(folder), safeRelative(relative))

    private fun safeRelative(raw: String): String {
        if (raw.isEmpty() || raw.startsWith('/') || raw.contains('\\') || raw.contains('\u0000')) {
            throw ExternalDeleteFailure("corrupt")
        }
        if (raw.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ExternalDeleteFailure("corrupt")
        }
        return raw
    }

    private fun writeEntry(folder: File, entry: ExternalDeleteEntry) {
        val file = File(folder, "entry.json")
        FileOutputStream(file).use { output ->
            output.write(json.encodeToString(ExternalDeleteEntry.serializer(), entry).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun removeTree(root: File) {
        if (!root.exists()) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private data class Route(val grantId: String, val path: WorkspacePath)

    private companion object {
        const val MAX_BYTES = 500L * 1024 * 1024
        const val MAX_ENTRIES = 50_000
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
        const val RETAINED_GROUPS = 3
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
