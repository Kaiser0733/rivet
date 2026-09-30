package com.kaiser.rivet.runtime

import android.content.Context
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MirrorFailure(val code: String, val path: String? = null) : Exception(code)

enum class MirrorSync { NoChanges, Ok, Conflict, Failed }
val MirrorSync.code: String get() = when (this) {
    MirrorSync.NoChanges -> "no_changes"
    MirrorSync.Ok -> "ok"
    MirrorSync.Conflict -> "conflict"
    MirrorSync.Failed -> "failed"
}
data class MirrorSyncResult(val state: MirrorSync, val path: String? = null)
data class MirrorReady(val worktree: File, val dirty: Boolean)

@Serializable
private data class MirrorEntry(val directory: Boolean, val size: Long? = null, val sha256: String? = null)

@Serializable
private data class MirrorBaseline(val tree: String, val entries: Map<String, MirrorEntry>)

@Serializable
private data class PartialCreate(
    val tree: String,
    val path: String,
    val documentId: String,
    val target: MirrorEntry,
)

private val emptyFile = MirrorEntry(false, 0, MessageDigest.getInstance("SHA-256").digest().toHex())

/** One SAF tree has one private worktree. The baseline names bytes confirmed at the last sync. */
class WorkspaceMirror(
    context: Context,
    private val workspace: SafWorkspace,
    private val isSelected: suspend () -> Boolean,
) {
    private val id = MessageDigest.getInstance("SHA-256")
        .digest(workspace.tree.toString().toByteArray()).toHex()
    private val base = File(context.filesDir, "runtime/workspaces/$id")
    private val current = File(base, "current")
    private val staging = File(base, "staging")
    private val checkpointStaging = File(base, "checkpoint-stage")
    private val previous = File(base, "previous")
    private val partialCreateFile = File(current, "partial-create.json")
    private val mutex = Mutex()
    val worktree: File get() = File(current, "worktree")
    val home: File get() = File(base, "home")
    val temporary: File get() = File(base, "tmp")

    suspend fun prepare(): MirrorReady = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected()
            recover()
            if (!current.exists()) materialize()
            val baseline = baseline()
            val local = localSnapshot()
            val dirty = local != baseline.entries || readPartialCreate() != null
            if (!dirty && safSnapshot() != baseline.entries) materialize()
            MirrorReady(worktree, dirty)
        }
    }

    suspend fun hasLocalChanges(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!current.exists()) false else localSnapshot() != baseline().entries || readPartialCreate() != null
        }
    }

    suspend fun sync(): MirrorSyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected()
            recover()
            if (!current.exists()) materialize()
            val before = baseline()
            val local = localSnapshot()
            val pendingCreate = readPartialCreate()
            if (local == before.entries) {
                if (pendingCreate != null) clearPartialCreate()
                return@withLock MirrorSyncResult(
                    if (pendingCreate == null) MirrorSync.NoChanges else MirrorSync.Conflict,
                    pendingCreate?.path,
                )
            }
            val external = safSnapshot()
            var partial = pendingCreate
            if (partial != null) {
                val path = partial.path
                val sameDocument = try {
                    workspace.stat(WorkspacePath.parse(path)).documentId == partial.documentId
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { false }
                if (!sameDocument || before.entries[path] != null || local[path] != partial.target ||
                    (external[path] != emptyFile && external[path] != partial.target)) {
                    clearPartialCreate()
                    return@withLock MirrorSyncResult(MirrorSync.Conflict, path)
                }
                if (external[path] == partial.target) {
                    clearPartialCreate()
                    partial = null
                }
            }
            // Resume only when every SAF entry is still at the baseline or already
            // matches this mirror's exact target; third-party content blocks writes.
            val difference = (external.keys + before.entries.keys + local.keys).firstOrNull {
                external[it] != before.entries[it] && external[it] != local[it] &&
                    !(it == partial?.path && external[it] == emptyFile)
            }
            if (difference != null) return@withLock MirrorSyncResult(MirrorSync.Conflict, difference)
            try {
                val removed = before.entries.keys.filter { it.isNotEmpty() &&
                    (it !in local || local[it]?.directory != before.entries[it]?.directory) }
                    .sortedByDescending { it.count { char -> char == '/' } }
                for (path in removed) {
                    selected()
                    if (external[path] == null) continue
                    val old = before.entries[path]!!
                    workspace.deleteIfUnchanged(WorkspacePath.parse(path),
                        if (old.directory) null else SafWorkspace.BinaryFingerprint(
                            old.size ?: throw MirrorFailure("baseline_invalid", path),
                            old.sha256 ?: throw MirrorFailure("baseline_invalid", path)))
                }
                val addedDirs = local.filter { (path, entry) -> path.isNotEmpty() && entry.directory &&
                    before.entries[path] != entry }.keys.sortedBy { it.count { char -> char == '/' } }
                for (path in addedDirs) {
                    selected()
                    if (external[path]?.directory == true) continue
                    val created = workspace.createDirectory(WorkspacePath.parse(path))
                    if (created.path.value != path) return@withLock MirrorSyncResult(MirrorSync.Failed, created.path.value)
                }
                val changedFiles = local.filter { (path, entry) -> !entry.directory &&
                    entry != before.entries[path] }.keys.sorted()
                for (path in changedFiles) {
                    selected()
                    if (external[path] == local[path]) continue
                    val relative = WorkspacePath.parse(path)
                    val file = File(worktree, path)
                    if (Files.isSymbolicLink(file.toPath())) throw MirrorFailure("unsafe_entry", path)
                    val old = before.entries[path]
                    val actual = if (old == null || old.directory) {
                        if (partial?.path == path) {
                            emptyFile.sha256!!
                        } else {
                            val created = workspace.createFile(relative)
                            if (created.path.value != path) return@withLock MirrorSyncResult(MirrorSync.Failed, created.path.value)
                            val fingerprint = workspace.fingerprint(created.path)
                            if (fingerprint.size != emptyFile.size || fingerprint.sha256 != emptyFile.sha256) {
                                return@withLock MirrorSyncResult(MirrorSync.Conflict, path)
                            }
                            partial = PartialCreate(workspace.tree.toString(), path, created.documentId, local[path]!!)
                            writePartialCreate(partial)
                            emptyFile.sha256!!
                        }
                    } else old.sha256 ?: throw MirrorFailure("baseline_invalid", path)
                    val createdId = partial?.takeIf { it.path == path }?.documentId
                    try {
                        FileInputStream(file).use { workspace.writeFileFrom(relative, it, actual, createdId) }
                    } catch (failure: WorkspaceFailure) {
                        if (failure.reason == WorkspaceFailure.Reason.CONFLICT) {
                            if (createdId != null) clearPartialCreate()
                            return@withLock MirrorSyncResult(MirrorSync.Conflict, path)
                        }
                        throw failure
                    }
                    if (partial?.path == path) {
                        clearPartialCreate()
                        partial = null
                    }
                }
                val verified = safSnapshot()
                val mismatch = (verified.keys + local.keys).firstOrNull { verified[it] != local[it] }
                if (mismatch != null) return@withLock MirrorSyncResult(MirrorSync.Failed, mismatch)
                writeBaseline(current, MirrorBaseline(workspace.tree.toString(), verified))
                clearPartialCreate()
                MirrorSyncResult(MirrorSync.Ok)
            } catch (e: CancellationException) { throw e
            } catch (e: MirrorFailure) { throw e
            } catch (e: Exception) { MirrorSyncResult(MirrorSync.Failed) }
        }
    }

    /** Explicitly abandon only this tree's unsynchronized private copy; SAF is read-only here. */
    suspend fun discardPendingChanges(): MirrorReady = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected()
            recover()
            materialize(discardPending = true)
            selected()
            MirrorReady(worktree, dirty = false)
        }
    }

    suspend fun installCheckpoint(replacement: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected()
            recover()
            if (!replacement.isDirectory || Files.isSymbolicLink(replacement.toPath())) throw MirrorFailure("unsafe_entry")
            val before = baseline()
            if (localSnapshot() != before.entries) throw MirrorFailure("mirror_dirty")
            if (safSnapshot() != before.entries) throw MirrorFailure("conflict")
            if (!checkpointStaging.mkdirs()) throw MirrorFailure("storage")
            try {
                Files.move(replacement.toPath(), File(checkpointStaging, "worktree").toPath(),
                    StandardCopyOption.ATOMIC_MOVE)
                writeBaseline(checkpointStaging, before)
                Files.move(current.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
                try {
                    Files.move(checkpointStaging.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (error: Exception) {
                    Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    throw error
                }
                if (previous.exists()) removeTree(previous)
            } catch (error: CancellationException) { throw error
            } catch (error: MirrorFailure) { throw error
            } catch (_: Exception) { throw MirrorFailure("restore_failed") }
        }
    }

    private suspend fun selected() {
        currentCoroutineContext().ensureActive()
        if (!isSelected()) throw MirrorFailure("workspace_changed")
    }

    private fun baseline(directory: File = current): MirrorBaseline {
        val file = File(directory, "baseline.json")
        val value = try { Json.decodeFromString(MirrorBaseline.serializer(), file.readText()) }
            catch (_: Exception) { throw MirrorFailure("baseline_invalid") }
        if (value.tree != workspace.tree.toString() || value.entries[""]?.directory != true) {
            throw MirrorFailure("baseline_invalid")
        }
        return value
    }

    private fun writeBaseline(directory: File, value: MirrorBaseline) {
        val target = File(directory, "baseline.json")
        val temporary = File(directory, "baseline.next")
        FileOutputStream(temporary).use { output ->
            output.write(Json.encodeToString(MirrorBaseline.serializer(), value).toByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readPartialCreate(): PartialCreate? {
        if (!partialCreateFile.exists()) return null
        val receipt = try { Json.decodeFromString(PartialCreate.serializer(), partialCreateFile.readText()) }
            catch (_: Exception) { throw MirrorFailure("receipt_invalid") }
        if (receipt.tree != workspace.tree.toString() || receipt.path.isEmpty() || receipt.target.directory) {
            throw MirrorFailure("receipt_invalid")
        }
        try { WorkspacePath.parse(receipt.path) }
            catch (_: Exception) { throw MirrorFailure("receipt_invalid") }
        return receipt
    }

    private fun writePartialCreate(receipt: PartialCreate) {
        val next = File(current, "partial-create.next")
        FileOutputStream(next).use { output ->
            output.write(Json.encodeToString(PartialCreate.serializer(), receipt).toByteArray())
            output.fd.sync()
        }
        Files.move(next.toPath(), partialCreateFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun clearPartialCreate() {
        Files.deleteIfExists(partialCreateFile.toPath())
    }

    private suspend fun materialize(discardPending: Boolean = false) {
        selected()
        base.mkdirs()
        removeStaging()
        val target = File(staging, "worktree")
        if (!target.mkdirs()) throw MirrorFailure("storage")
        try {
            val entries = safSnapshot(target)
            writeBaseline(staging, MirrorBaseline(workspace.tree.toString(), entries))
            if (discardPending) {
                if (safSnapshot() != entries) throw MirrorFailure("conflict")
                // The marker travels with the new baseline. After process death it
                // authorizes removal of the old dirty copy only after installation.
                FileOutputStream(File(staging, "discard-previous")).use { output ->
                    output.write(workspace.tree.toString().toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            }
            selected()
            if (current.exists()) {
                val local = localSnapshot()
                val before = baseline()
                if (!discardPending && local != before.entries) throw MirrorFailure("mirror_dirty")
                Files.move(current.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(staging.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                if (previous.exists()) Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
            if (previous.exists()) removeTree(previous)
            Files.deleteIfExists(File(current, "discard-previous").toPath())
        } catch (e: CancellationException) { throw e
        } catch (e: MirrorFailure) { throw e
        } catch (_: Exception) { throw MirrorFailure("materialize_failed") }
    }

    private suspend fun safSnapshot(target: File? = null): Map<String, MirrorEntry> {
        val entries = linkedMapOf("" to MirrorEntry(directory = true))
        suspend fun visit(parent: WorkspacePath) {
            selected()
            for (entry in workspace.listDirectory(parent)) {
                selected()
                val path = entry.path.value
                if (path in entries) throw MirrorFailure("duplicate", path)
                val dest = target?.let { File(it, path) }
                if (entry.directory) {
                    if (dest != null && !dest.mkdir()) throw MirrorFailure("materialize_failed", path)
                    entries[path] = MirrorEntry(directory = true)
                    visit(entry.path)
                } else {
                    val fingerprint = if (dest == null) workspace.fingerprint(entry.path)
                    else {
                        if (entry.size != null && entry.size > dest.parentFile!!.usableSpace - 8L * 1024 * 1024) {
                            throw MirrorFailure("storage", path)
                        }
                        FileOutputStream(dest).use { workspace.copyFileTo(entry.path, it) }
                    }
                    entries[path] = MirrorEntry(false, fingerprint.size, fingerprint.sha256)
                }
            }
        }
        visit(WorkspacePath.ROOT)
        return entries
    }

    private suspend fun localSnapshot(root: File = worktree): Map<String, MirrorEntry> {
        if (Files.isSymbolicLink(root.toPath())) throw MirrorFailure("unsafe_entry")
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) throw MirrorFailure("mirror_missing")
        val entries = linkedMapOf("" to MirrorEntry(directory = true))
        suspend fun visit(parent: Path, relative: WorkspacePath) {
            Files.newDirectoryStream(parent).use { children ->
                for (child in children) {
                    selected()
                    val path = relative.child(child.fileName.toString())
                    val name = path.value
                    when {
                        Files.isSymbolicLink(child) -> throw MirrorFailure("unsafe_entry", name)
                        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> {
                            entries[name] = MirrorEntry(directory = true)
                            visit(child, path)
                        }
                        Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                            val digest = MessageDigest.getInstance("SHA-256")
                            var length = 0L
                            FileInputStream(child.toFile()).use { input ->
                                val bytes = ByteArray(64 * 1024)
                                while (true) {
                                    selected()
                                    val count = input.read(bytes)
                                    if (count < 0) break
                                    digest.update(bytes, 0, count)
                                    length += count
                                }
                            }
                            entries[name] = MirrorEntry(false, length, digest.digest().toHex())
                        }
                        else -> throw MirrorFailure("unsafe_entry", name)
                    }
                }
            }
        }
        visit(root.toPath(), WorkspacePath.ROOT)
        return entries
    }

    private suspend fun recover() {
        selected()
        // Commands share the app UID; refuse a substituted runtime directory
        // before opening metadata or removing a private tree.
        listOf(base.parentFile!!.parentFile!!, base.parentFile!!, base, current,
            previous, staging, checkpointStaging).forEach { directory ->
            if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(directory.toPath()))) throw MirrorFailure("unsafe_entry")
        }
        if (!base.isDirectory && !base.mkdirs()) throw MirrorFailure("storage")
        if (!current.exists() && previous.exists()) {
            Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        val discardMarker = File(current, "discard-previous")
        val discardInstalled = Files.exists(discardMarker.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (discardInstalled) {
            if (!Files.isRegularFile(discardMarker.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                discardMarker.length() > 8192 || discardMarker.readText() != workspace.tree.toString() ||
                localSnapshot() != baseline().entries) throw MirrorFailure("discard_invalid")
        }
        if (previous.exists()) {
            // Without an installed, verified discard marker, dirty previous work
            // still blocks recovery instead of being silently thrown away.
            if (!discardInstalled && localSnapshot(File(previous, "worktree")) != baseline(previous).entries) {
                throw MirrorFailure("previous_dirty")
            }
            removeTree(previous)
        }
        if (discardInstalled) Files.delete(discardMarker.toPath())
        removeStaging()
    }

    private fun removeStaging() {
        if (staging.exists()) removeTree(staging)
        if (checkpointStaging.exists()) removeTree(checkpointStaging)
    }

    private fun removeTree(root: File) {
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
