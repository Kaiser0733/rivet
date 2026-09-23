package com.kaiser.rivet.runtime

import android.content.Context
import com.kaiser.rivet.workspace.SafWorkspace
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
    private val previous = File(base, "previous")
    private val mutex = Mutex()
    val worktree: File get() = File(current, "worktree")
    val home: File get() = File(base.parentFile!!.parentFile!!, "home")
    val temporary: File get() = File(base, "tmp")

    suspend fun prepare(): MirrorReady = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected()
            recover()
            if (!current.exists()) materialize()
            val baseline = baseline()
            val local = localSnapshot()
            val dirty = local != baseline.entries
            if (!dirty && safSnapshot() != baseline.entries) materialize()
            MirrorReady(worktree, dirty)
        }
    }

    suspend fun hasLocalChanges(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!current.exists()) false else localSnapshot() != baseline().entries
        }
    }

    suspend fun sync(): MirrorSyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected()
            recover()
            if (!current.exists()) materialize()
            val before = baseline()
            val local = localSnapshot()
            if (local == before.entries) return@withLock MirrorSyncResult(MirrorSync.NoChanges)
            val external = safSnapshot()
            // Global preflight is deliberately conservative: no partial writes
            // begin when any external change makes this mirror's baseline stale.
            val difference = (external.keys + before.entries.keys).firstOrNull {
                external[it] != before.entries[it]
            }
            if (difference != null) return@withLock MirrorSyncResult(MirrorSync.Conflict, difference)
            try {
                val removed = before.entries.keys.filter { it.isNotEmpty() &&
                    (it !in local || local[it]?.directory != before.entries[it]?.directory) }
                    .sortedByDescending { it.count { char -> char == '/' } }
                for (path in removed) {
                    selected()
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
                    val created = workspace.createDirectory(WorkspacePath.parse(path))
                    if (created.path.value != path) return@withLock MirrorSyncResult(MirrorSync.Failed, created.path.value)
                }
                val changedFiles = local.filter { (path, entry) -> !entry.directory &&
                    entry != before.entries[path] }.keys.sorted()
                for (path in changedFiles) {
                    selected()
                    val relative = WorkspacePath.parse(path)
                    val file = File(worktree, path)
                    if (Files.isSymbolicLink(file.toPath())) throw MirrorFailure("unsafe_entry", path)
                    val old = before.entries[path]
                    val actual = if (old == null || old.directory) {
                        val created = workspace.createFile(relative)
                        if (created.path.value != path) return@withLock MirrorSyncResult(MirrorSync.Failed, created.path.value)
                        workspace.fingerprint(created.path).sha256
                    } else old.sha256 ?: throw MirrorFailure("baseline_invalid", path)
                    FileInputStream(file).use { workspace.writeFileFrom(relative, it, actual) }
                }
                val verified = safSnapshot()
                val mismatch = (verified.keys + local.keys).firstOrNull { verified[it] != local[it] }
                if (mismatch != null) return@withLock MirrorSyncResult(MirrorSync.Failed, mismatch)
                writeBaseline(current, MirrorBaseline(workspace.tree.toString(), verified))
                MirrorSyncResult(MirrorSync.Ok)
            } catch (e: CancellationException) { throw e
            } catch (e: MirrorFailure) { throw e
            } catch (e: Exception) { MirrorSyncResult(MirrorSync.Failed) }
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

    private suspend fun materialize() {
        selected()
        base.mkdirs()
        removeStaging()
        val target = File(staging, "worktree")
        if (!target.mkdirs()) throw MirrorFailure("storage")
        try {
            val entries = safSnapshot(target)
            writeBaseline(staging, MirrorBaseline(workspace.tree.toString(), entries))
            selected()
            if (current.exists()) {
                if (localSnapshot() != baseline().entries) throw MirrorFailure("mirror_dirty")
                Files.move(current.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(staging.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                if (previous.exists()) Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
            if (previous.exists()) removeTree(previous)
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
        if (!root.isDirectory) throw MirrorFailure("mirror_missing")
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
        base.mkdirs()
        if (!current.exists() && previous.exists()) {
            Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        if (previous.exists()) {
            // A previous tree is discarded only when its own baseline proves
            // it had no unsynchronized runtime edits at the swap boundary.
            if (localSnapshot(File(previous, "worktree")) != baseline(previous).entries) {
                throw MirrorFailure("previous_dirty")
            }
            removeTree(previous)
        }
        removeStaging()
    }

    private fun removeStaging() {
        if (staging.exists()) removeTree(staging)
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
