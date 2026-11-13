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
data class MirrorSyncResult(val state: MirrorSync, val path: String? = null,
                            val skipped: List<String> = emptyList(),
                            val conflicts: List<String> = emptyList())

// One conflicted file must not read as "the whole project is dirty", so every
// comparison drops the paths the baseline recorded as unresolvable.
private fun Map<String, MirrorEntry>.withoutExcluded(excluded: Set<String>): Map<String, MirrorEntry> =
    if (excluded.isEmpty()) this else filterKeys { it !in excluded }
data class MirrorReady(val worktree: File, val dirty: Boolean)

@Serializable
private data class MirrorEntry(val directory: Boolean, val size: Long? = null, val sha256: String? = null)

@Serializable
private data class MirrorBaseline(val tree: String, val entries: Map<String, MirrorEntry>,
                                  // Paths where the project and this mirror disagree and
                                  // neither side may be overwritten. Comparisons skip
                                  // them so one blocked file cannot lock the whole tree.
                                  val excluded: Set<String> = emptySet())

@Serializable
private data class PartialCreate(
    val tree: String,
    val path: String,
    val documentId: String,
    val target: MirrorEntry,
    val actualPath: String? = null,
)

private fun isExcludedPath(path: String, excluded: Set<String>): Boolean =
    path in excluded || excluded.any { path.startsWith("$it/") || it.startsWith("$path/") }

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
    private val vcsCarry = File(base, "vcs-carry")
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
            val dirty = local.withoutExcluded(baseline.excluded) !=
                baseline.entries.withoutExcluded(baseline.excluded) || readPartialCreate() != null
            if (!dirty && safSnapshot().withoutExcluded(baseline.excluded) !=
                baseline.entries.withoutExcluded(baseline.excluded)) materialize()
            MirrorReady(worktree, dirty)
        }
    }

    suspend fun hasLocalChanges(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!current.exists()) false else {
                val baseline = baseline()
                localSnapshot().withoutExcluded(baseline.excluded) !=
                    baseline.entries.withoutExcluded(baseline.excluded) || readPartialCreate() != null
            }
        }
    }

    suspend fun sync(): MirrorSyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected()
            recover()
            if (!current.exists()) materialize()
            val before = baseline()
            val skipped = mutableListOf<String>()
            val local = localSnapshot(skipped = skipped)
            val pendingCreate = readPartialCreate()
            // Fast path only when nothing is blocked: a recorded conflict has to go
            // through the full pass below so it is re-checked and can clear.
            if (before.excluded.isEmpty() && local == before.entries) {
                if (pendingCreate != null) {
                    if (local[pendingCreate.path] == null) clearPartialCreate()
                    return@withLock MirrorSyncResult(MirrorSync.Conflict, pendingCreate.path, skipped)
                }
                val externalOnly = safSnapshot()
                if (externalOnly == before.entries) {
                    return@withLock MirrorSyncResult(MirrorSync.NoChanges, skipped = skipped)
                }
                // No pending mirror work: SAF is authoritative. Adopt external
                // changes wholesale instead of reporting a conflict that has no
                // overlapping local edit.
                materialize()
                return@withLock MirrorSyncResult(MirrorSync.Ok, skipped = skipped)
            }
            val external = safSnapshot()
            var partial = pendingCreate
            if (partial != null) {
                val path = partial.path
                if (partial.actualPath != null) {
                    if (local[path] == null) {
                        clearPartialCreate()
                        partial = null
                    } else {
                        // The provider normalized the created filename earlier; keep the
                        // receipt intact so subsequent syncs do not create duplicates.
                        return@withLock MirrorSyncResult(MirrorSync.Failed, partial.actualPath, skipped)
                    }
                } else {
                    val sameDocument = try {
                        workspace.stat(WorkspacePath.parse(path)).documentId == partial.documentId
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { false }
                    if (!sameDocument || before.entries[path] != null || local[path] != partial.target ||
                        (external[path] != emptyFile && external[path] != partial.target)) {
                        clearPartialCreate()
                        return@withLock MirrorSyncResult(MirrorSync.Conflict, path, skipped)
                    }
                    if (external[path] == partial.target) {
                        clearPartialCreate()
                        partial = null
                    }
                }
            }
            // A path is a true overlap only when BOTH sides moved away from the
            // baseline and they disagree. An external-only change is adopted below;
            // a local-only change is pushed below. A path whose two sides now agree
            // is no longer blocked.
            val localChanged = (local.keys + before.entries.keys).filter {
                local[it] != before.entries[it]
            }.toSet()
            val externalChanged = (external.keys + before.entries.keys).filter {
                external[it] != before.entries[it]
            }.toSet()
            val excluded = before.excluded.toMutableSet()
            // A recorded block clears only when both sides now agree. An external
            // side that merely still equals the baseline it was blocked against is
            // not progress: retrying must not overwrite the local copy.
            excluded.removeAll { path -> external[path] == local[path] }
            excluded.addAll((localChanged intersect externalChanged).filter {
                external[it] != local[it] &&
                    !(it == partial?.path && external[it] == emptyFile)
            })
            try {
                val removed = before.entries.keys.filter { it.isNotEmpty() &&
                    !isExcludedPath(it, excluded) &&
                    (it !in local || local[it]?.directory != before.entries[it]?.directory) }
                    .sortedByDescending { it.count { char -> char == '/' } }
                for (path in removed) {
                    if (isExcludedPath(path, excluded)) continue
                    selected()
                    if (external[path] == null) continue
                    val old = before.entries[path]!!
                    try {
                        workspace.deleteIfUnchanged(WorkspacePath.parse(path),
                            if (old.directory) null else SafWorkspace.BinaryFingerprint(
                                old.size ?: throw MirrorFailure("baseline_invalid", path),
                                old.sha256 ?: throw MirrorFailure("baseline_invalid", path)))
                    } catch (failure: WorkspaceFailure) {
                        if (failure.reason == WorkspaceFailure.Reason.CONFLICT) {
                            excluded += path
                        } else {
                            return@withLock MirrorSyncResult(MirrorSync.Failed, path, skipped)
                        }
                    }
                }
                val addedDirs = local.filter { (path, entry) -> path.isNotEmpty() && entry.directory &&
                    !isExcludedPath(path, excluded) && before.entries[path] != entry }
                    .keys.sortedBy { it.count { char -> char == '/' } }
                for (path in addedDirs) {
                    if (isExcludedPath(path, excluded)) continue
                    selected()
                    if (external[path]?.directory == true) continue
                    if (external[path]?.directory == false) {
                        excluded += path
                        continue
                    }
                    try {
                        val created = workspace.createDirectory(WorkspacePath.parse(path))
                        if (created.path.value != path) return@withLock MirrorSyncResult(MirrorSync.Failed, created.path.value, skipped)
                    } catch (failure: WorkspaceFailure) {
                        if (failure.reason == WorkspaceFailure.Reason.CONFLICT) {
                            excluded += path
                        } else {
                            return@withLock MirrorSyncResult(MirrorSync.Failed, path, skipped)
                        }
                    }
                }
                val changedFiles = local.filter { (path, entry) -> !entry.directory &&
                    !isExcludedPath(path, excluded) && entry != before.entries[path] }.keys.sorted()
                for (path in changedFiles) {
                    if (isExcludedPath(path, excluded)) continue
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
                            val created = try {
                                workspace.createFile(relative)
                            } catch (failure: WorkspaceFailure) {
                                if (failure.reason == WorkspaceFailure.Reason.CONFLICT) {
                                    excluded += path
                                    continue
                                }
                                return@withLock MirrorSyncResult(MirrorSync.Failed, path, skipped)
                            }
                            if (created.path.value != path) {
                                // The provider gave the document a different name, so this
                                // path can never be written. Keep the receipt: it is what
                                // stops the next sync creating a second copy of it.
                                writePartialCreate(PartialCreate(workspace.tree.toString(), path,
                                    created.documentId, local[path]!!, actualPath = created.path.value))
                                return@withLock MirrorSyncResult(MirrorSync.Failed, created.path.value, skipped)
                            }
                            val fingerprint = workspace.fingerprint(created.path)
                            if (fingerprint.size != emptyFile.size || fingerprint.sha256 != emptyFile.sha256) {
                                return@withLock MirrorSyncResult(MirrorSync.Conflict, path, skipped)
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
                            return@withLock MirrorSyncResult(MirrorSync.Conflict, path, skipped)
                        }
                        // Name the file that could not be written; a bare "failed" tells
                        // the user nothing about which path needs attention.
                        return@withLock MirrorSyncResult(MirrorSync.Failed, path, skipped)
                    }
                    if (partial?.path == path) {
                        clearPartialCreate()
                        partial = null
                    }
                }
                // Pull SAF-only changes into the private worktree. This is the
                // difference between "the user edited the project" and "both sides
                // edited the same path". Without it, a non-overlapping external
                // change would be baselined but left stale in the mirror forever.
                adoptExternalOnly(external, before.entries, localChanged, excluded)
                val verified = safSnapshot()
                val finalSkipped = mutableListOf<String>()
                val updatedLocal = localSnapshot(skipped = finalSkipped)
                val mismatch = (verified.keys + updatedLocal.keys).firstOrNull {
                    !isExcludedPath(it, excluded) && verified[it] != updatedLocal[it]
                }
                if (mismatch != null) return@withLock MirrorSyncResult(MirrorSync.Failed, mismatch, finalSkipped)
                writeBaseline(current, MirrorBaseline(workspace.tree.toString(), verified,
                    excluded.toSet()))
                clearPartialCreate()
                MirrorSyncResult(
                    if (excluded.isEmpty()) MirrorSync.Ok else MirrorSync.Conflict,
                    excluded.sorted().firstOrNull(),
                    finalSkipped,
                    excluded.sorted(),
                )
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
            stripVcsDirs(replacement)
            val before = baseline()
            if (localSnapshot().withoutExcluded(before.excluded) !=
                before.entries.withoutExcluded(before.excluded)) throw MirrorFailure("mirror_dirty")
            if (safSnapshot().withoutExcluded(before.excluded) !=
                before.entries.withoutExcluded(before.excluded)) throw MirrorFailure("conflict")
            if (!checkpointStaging.mkdirs()) throw MirrorFailure("storage")
            var carried = 0
            try {
                carried = WorktreeVcsCarry(vcsCarry).take(worktree)
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
                if (carried > 0) WorktreeVcsCarry(vcsCarry).restore(worktree)
                if (previous.exists()) removeTree(previous)
            } catch (error: CancellationException) { throw error
            } catch (error: MirrorFailure) { throw error
            } catch (_: Exception) { throw MirrorFailure("restore_failed") }
            finally {
                if (carried > 0) runCatching { WorktreeVcsCarry(vcsCarry).restore(worktree) }
            }
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
        // A baseline written by an older build may still list .git. Ignore those
        // entries instead of reporting a permanent dirty tree after the exclusion.
        return value.copy(
            entries = value.entries.filterKeys { !isVcsPath(it) },
            excluded = value.excluded.filterNot(::isVcsPath).toSet(),
        )
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
            var carried = 0
            val hadCurrent = current.exists()
            if (hadCurrent) {
                val local = localSnapshot()
                val before = baseline()
                if (!discardPending && local.withoutExcluded(before.excluded) !=
                    before.entries.withoutExcluded(before.excluded)) throw MirrorFailure("mirror_dirty")
            }
            try {
                if (hadCurrent) {
                    carried = WorktreeVcsCarry(vcsCarry).take(worktree)
                    Files.move(current.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                Files.move(staging.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (carried > 0) WorktreeVcsCarry(vcsCarry).restore(worktree)
            } catch (e: Exception) {
                if (previous.exists()) Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (carried > 0) runCatching { WorktreeVcsCarry(vcsCarry).restore(worktree) }
                throw e
            }
            if (previous.exists()) removeTree(previous)
            Files.deleteIfExists(File(current, "discard-previous").toPath())
        } catch (e: CancellationException) { throw e
        } catch (e: MirrorFailure) { throw e
        } catch (_: Exception) { throw MirrorFailure("materialize_failed") }
    }

    /** Replace only unchanged-in-the-mirror paths with SAF's newer version. */
    private suspend fun adoptExternalOnly(
        external: Map<String, MirrorEntry>,
        before: Map<String, MirrorEntry>,
        localChanged: Set<String>,
        excluded: Set<String>,
    ) {
        val paths = (external.keys + before.keys).filter { path ->
            path.isNotEmpty() && external[path] != before[path] &&
                !isExcludedPath(path, localChanged) && !isExcludedPath(path, excluded)
        }
        // Deletions first, deepest first, so a missing external directory cannot
        // be recreated by a later child write.
        for (path in paths.filter { external[it] == null }
            .sortedByDescending { it.count { ch -> ch == '/' } }) {
            selected()
            val target = File(worktree, path)
            if (Files.isSymbolicLink(target.toPath())) {
                Files.deleteIfExists(target.toPath())
            } else if (Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                removeTree(target)
            } else {
                Files.deleteIfExists(target.toPath())
            }
        }
        // Directories before files.
        for (path in paths.filter { external[it]?.directory == true }
            .sortedBy { it.count { ch -> ch == '/' } }) {
            selected()
            requireNoSymlinkParents(path)
            val target = File(worktree, path)
            if (Files.isSymbolicLink(target.toPath())) Files.deleteIfExists(target.toPath())
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !target.isDirectory) {
                if (Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) removeTree(target)
                else Files.deleteIfExists(target.toPath())
            }
            if (!target.isDirectory && !target.mkdirs()) {
                throw MirrorFailure("materialize_failed", path)
            }
        }
        for (path in paths.filter { external[it]?.directory == false }.sorted()) {
            selected()
            requireNoSymlinkParents(path)
            val target = File(worktree, path)
            if (Files.isSymbolicLink(target.toPath())) Files.deleteIfExists(target.toPath())
            if (Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) removeTree(target)
            target.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw MirrorFailure("materialize_failed", path)
                }
            }
            try {
                FileOutputStream(target).use {
                    workspace.copyFileTo(WorkspacePath.parse(path), it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MirrorFailure) {
                throw e
            } catch (_: Exception) {
                throw MirrorFailure("materialize_failed", path)
            }
        }
    }

    private fun requireNoSymlinkParents(path: String) {
        var parent = File(worktree, path).parentFile
        while (parent != null && parent.path.length >= worktree.path.length) {
            if (Files.isSymbolicLink(parent.toPath())) throw MirrorFailure("unsafe_entry", path)
            if (parent == worktree) break
            parent = parent.parentFile
        }
    }

    private suspend fun safSnapshot(target: File? = null): Map<String, MirrorEntry> {
        val entries = linkedMapOf("" to MirrorEntry(directory = true))
        suspend fun visit(parent: WorkspacePath) {
            selected()
            for (entry in workspace.listDirectory(parent)) {
                selected()
                val path = entry.path.value
                if (isVcsPath(path)) continue
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

    // Symlinks and special files are not project content Rivet can mirror: commands
    // create them (node_modules/.bin, venv/bin). Skipping the entry instead of
    // throwing keeps the rest of the tree syncable; the caller reports the paths.
    private suspend fun localSnapshot(root: File = worktree,
                                      skipped: MutableList<String>? = null): Map<String, MirrorEntry> {
        if (Files.isSymbolicLink(root.toPath())) throw MirrorFailure("unsafe_entry")
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) throw MirrorFailure("mirror_missing")
        val entries = linkedMapOf("" to MirrorEntry(directory = true))
        suspend fun visit(parent: Path, relative: WorkspacePath) {
            Files.newDirectoryStream(parent).use { children ->
                for (child in children) {
                    selected()
                    val childName = child.fileName.toString()
                    val rawPath = if (relative.isRoot) childName else "${relative.value}/$childName"
                    // Terminal-run git commands create this at the worktree root.
                    if (isVcsPath(rawPath)) continue
                    val path = try {
                        relative.child(childName)
                    } catch (_: WorkspaceFailure) {
                        skipped?.add(rawPath)
                        continue
                    }
                    val name = path.value
                    when {
                        Files.isSymbolicLink(child) -> skipped?.add(name)
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
                        else -> skipped?.add(name)
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
            previous, staging, checkpointStaging, vcsCarry).forEach { directory ->
            if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(directory.toPath()))) throw MirrorFailure("unsafe_entry")
        }
        if (!base.isDirectory && !base.mkdirs()) throw MirrorFailure("storage")
        if (!current.exists() && previous.exists()) {
            Files.move(previous.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        if (vcsCarry.isDirectory && current.isDirectory) {
            WorktreeVcsCarry(vcsCarry).restore(worktree)
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

    private fun stripVcsDirs(root: File) {
        for (name in VCS_DIRECTORIES) {
            val directory = File(root, name)
            if (Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) removeTree(directory)
        }
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
