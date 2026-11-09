package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspacePath
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class CheckpointFailure(val code: String) : Exception(code)

@Serializable
data class CheckpointEntry(val directory: Boolean, val size: Long = 0, val sha256: String? = null)

@Serializable
data class CheckpointRecord(
    val id: String,
    val workspace: String,
    val createdAt: Long,
    val before: Map<String, CheckpointEntry>,
    val after: Map<String, CheckpointEntry>? = null,
    val complete: Boolean = false,
    val undone: Boolean = false,
    val runId: String? = null,
    // Set when Undo restored Rivet's copy but could not write it back to the
    // project. Not a success, so it is not recorded as `undone`.
    val desynced: Boolean = false,
    // Paths Undo cannot cover because they are not regular project content
    // (symlinks, sockets). Recorded so the gap is visible instead of implied.
    val skipped: List<String> = emptyList(),
)

data class CheckpointChange(val path: String, val kind: String, val beforeSize: Long?, val afterSize: Long?)

/** One immutable pre-turn archive plus a post-turn fingerprint manifest. Both live in app storage. */
class TurnCheckpoint(private val storage: File, private val workspace: String) {
    private val directory = File(storage, sha256(workspace.toByteArray()))

    suspend fun begin(worktree: File, runId: String? = null): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val staging = File(directory, ".$id.tmp")
        if (!staging.mkdirs()) throw CheckpointFailure("storage")
        try {
            val archive = File(staging, "before.zip")
            val skipped = mutableListOf<String>()
            val before = FileOutputStream(archive).use { file ->
                ZipOutputStream(file).use { zip ->
                    zip.setLevel(1)
                    val snapshot = scan(worktree, zip, skipped)
                    zip.finish()
                    zip.flush()
                    file.fd.sync()
                    snapshot
                }
            }
            writeRecord(staging, CheckpointRecord(id, workspace, System.currentTimeMillis(), before,
                skipped = skipped, runId = runId))
            Files.move(staging.toPath(), File(directory, id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            id
        } catch (error: Exception) {
            removeTree(staging)
            throw error
        }
    }

    suspend fun finish(id: String, worktree: File): Boolean = withContext(Dispatchers.IO) {
        val folder = folder(id)
        val record = readRecord(folder)
        if (record.complete) return@withContext record.before != record.after
        val after = scan(worktree)
        if (record.after != null && record.after != after) throw CheckpointFailure("undo_conflict")
        if (record.before == after) {
            removeTree(folder)
            return@withContext false
        }
        writeRecord(folder, record.copy(after = after, complete = true))
        prune()
        true
    }

    suspend fun recordPost(id: String, worktree: File) = withContext(Dispatchers.IO) {
        val folder = folder(id)
        val record = readRecord(folder)
        if (record.complete) throw CheckpointFailure("checkpoint_closed")
        writeRecord(folder, record.copy(after = scan(worktree)))
    }

    suspend fun matchesPost(id: String, worktree: File): Boolean = withContext(Dispatchers.IO) {
        val post = readRecord(folder(id)).after
        post == null || scan(worktree) == post
    }

    suspend fun history(limit: Int = 50): List<CheckpointRecord> = withContext(Dispatchers.IO) {
        directory.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { folder -> try { readRecord(folder) } catch (_: Exception) { null } }
            ?.sortedByDescending { it.createdAt }
            ?.take(limit.coerceIn(1, 200))
            .orEmpty()
    }

    suspend fun latest(): CheckpointRecord? = withContext(Dispatchers.IO) {
        directory.listFiles()?.mapNotNull { folder ->
            if (!folder.isDirectory || folder.name.startsWith('.')) null
            else try { readRecord(folder) } catch (_: Exception) { null }
        }?.filter { it.complete && it.after != null && !it.undone && !it.desynced }
            ?.maxByOrNull { it.createdAt }
    }

    suspend fun changes(record: CheckpointRecord): List<CheckpointChange> = withContext(Dispatchers.IO) {
        val after = record.after ?: return@withContext emptyList()
        (record.before.keys + after.keys).distinct().sorted().mapNotNull { path ->
            val old = record.before[path]
            val new = after[path]
            if (old == new) null else CheckpointChange(path,
                when { old == null -> "added"; new == null -> "deleted"; else -> "modified" },
                old?.takeUnless { it.directory }?.size, new?.takeUnless { it.directory }?.size)
        }
    }

    suspend fun stageUndo(record: CheckpointRecord, current: File): File = withContext(Dispatchers.IO) {
        if (record.undone || !record.complete || record.after == null) throw CheckpointFailure("undo_unavailable")
        if (scan(current) != record.after) throw CheckpointFailure("undo_conflict")
        val folder = folder(record.id)
        val stage = File(folder, "restore-worktree")
        if (stage.exists()) removeTree(stage)
        if (!stage.mkdirs()) throw CheckpointFailure("storage")
        try {
            ZipFile(File(folder, "before.zip")).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.nextElement()
                    val relative = WorkspacePath.parse(entry.name.removeSuffix("/"))
                    if (relative.isRoot || relative.segments.any { it == ".git" }) throw CheckpointFailure("corrupt")
                    val target = File(stage, relative.value)
                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) throw CheckpointFailure("storage")
                    } else {
                        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw CheckpointFailure("storage")
                        archive.getInputStream(entry).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                    }
                }
            }
            preserveGitDirectories(current, stage)
            if (scan(stage) != record.before) throw CheckpointFailure("corrupt")
            stage
        } catch (error: Exception) {
            removeTree(stage)
            throw error
        }
    }

    suspend fun markUndone(id: String) = withContext(Dispatchers.IO) {
        val folder = folder(id)
        val record = readRecord(folder)
        writeRecord(folder, record.copy(undone = true))
    }

    suspend fun discardStagedUndo(id: String) = withContext(Dispatchers.IO) {
        removeTree(File(folder(id), "restore-worktree"))
    }

    suspend fun markDesynced(id: String) = withContext(Dispatchers.IO) {
        val folder = folder(id)
        val record = readRecord(folder)
        writeRecord(folder, record.copy(desynced = true))
    }

    // Deliberately small: directory patterns only, no negation, no `**`. That covers
    // the generated trees (node_modules, build, dist, .gradle, target) that push a
    // project past the entry cap, without a parser subtle enough to skip real source.
    private fun ignoredDirectories(root: File): List<String> {
        val patterns = mutableListOf<String>()
        listOf(".rivetignore", ".gitignore").forEach { name ->
            val file = File(root, name)
            if (!file.isFile) return@forEach
            file.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
                val cleaned = line.trimStart('/').trimEnd('/')
                if (cleaned.isEmpty() || cleaned.contains("**")) return@forEach
                patterns += cleaned
            }
        }
        return patterns
    }

    private fun isIgnored(path: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        val segments = path.split('/')
        return patterns.any { pattern ->
            val wanted = pattern.split('/')
            // A one-part pattern matches a folder name at any depth, as Git does.
            if (wanted.size == 1) segments.any { segmentMatches(wanted[0], it) }
            else segments.size >= wanted.size && wanted.indices.all {
                segmentMatches(wanted[it], segments[it])
            }
        }
    }

    private fun segmentMatches(pattern: String, segment: String): Boolean {
        if (pattern.isEmpty()) return false
        if (!pattern.contains('*')) return pattern == segment
        val regex = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
        return regex.matches(segment)
    }

    private suspend fun scan(root: File, zip: ZipOutputStream? = null,
                             skipped: MutableList<String>? = null): Map<String, CheckpointEntry> {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) throw CheckpointFailure("unsafe_entry")
        val entries = linkedMapOf<String, CheckpointEntry>()
        val context = currentCoroutineContext()
        val ignored = ignoredDirectories(root)
        var bytes = 0L
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                if (Files.isSymbolicLink(dir)) {
                    if (dir != root.toPath()) skipped?.add(relative(root, dir))
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (dir != root.toPath() && dir.fileName.toString() == ".git") return FileVisitResult.SKIP_SUBTREE
                if (dir != root.toPath() && relative(root, dir) in setOf(".rivet/runs", ".rivet/checkpoints")) {
                    // Run journals and run-checkpoint deltas change after a checkpoint's
                    // post snapshot; they are app state, not user files, and must not
                    // poison Undo.
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (dir != root.toPath()) {
                    val path = relative(root, dir)
                    if (isIgnored(path, ignored)) return FileVisitResult.SKIP_SUBTREE
                    entries[path] = CheckpointEntry(true)
                    zip?.putNextEntry(ZipEntry("$path/"))
                    zip?.closeEntry()
                }
                if (entries.size > MAX_ENTRIES) throw CheckpointFailure("checkpoint_limit")
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                if (file.fileName.toString() == ".git") return FileVisitResult.CONTINUE
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) {
                    // Commands leave these behind (node_modules/.bin, venv/bin).
                    // Undo cannot cover them, so record the gap and checkpoint the rest.
                    skipped?.add(relative(root, file))
                    return FileVisitResult.CONTINUE
                }
                val path = relative(root, file)
                bytes += attrs.size()
                if (bytes > MAX_CONTENT_BYTES || entries.size >= MAX_ENTRIES) throw CheckpointFailure("checkpoint_limit")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                if (zip != null) zip.putNextEntry(ZipEntry(path))
                FileInputStream(file.toFile()).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        zip?.write(buffer, 0, count)
                        size += count
                    }
                }
                zip?.closeEntry()
                entries[path] = CheckpointEntry(false, size, digest.digest().toHex())
                return FileVisitResult.CONTINUE
            }
        })
        return entries
    }

    private fun preserveGitDirectories(current: File, stage: File) {
        Files.walkFileTree(current.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir.fileName?.toString() == ".git") {
                    copyTree(dir, File(stage, relative(current, dir)).toPath())
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString() == ".git") {
                    val target = File(stage, relative(current, file))
                    target.parentFile!!.mkdirs()
                    Files.copy(file, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) throw CheckpointFailure("unsafe_entry")
                Files.createDirectories(target.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) throw CheckpointFailure("unsafe_entry")
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun relative(root: File, path: Path): String =
        root.toPath().relativize(path).joinToString("/") { it.toString() }

    private fun folder(id: String): File {
        if (!Regex("[0-9a-fA-F-]{36}").matches(id)) throw CheckpointFailure("invalid_checkpoint")
        return File(directory, id)
    }

    private fun readRecord(folder: File): CheckpointRecord = try {
        Json.decodeFromString<CheckpointRecord>(File(folder, "checkpoint.json").readText()).also {
            if (it.workspace != workspace || it.id != folder.name) throw CheckpointFailure("corrupt")
        }
    } catch (error: CheckpointFailure) { throw error
    } catch (_: Exception) { throw CheckpointFailure("corrupt") }

    private fun writeRecord(folder: File, record: CheckpointRecord) {
        val next = File(folder, "checkpoint.next")
        FileOutputStream(next).use { output ->
            output.write(Json.encodeToString(CheckpointRecord.serializer(), record).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(next.toPath(), File(folder, "checkpoint.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun prune() {
        val all = storage.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .sortedByDescending { it.lastModified() }
        var retained = 0
        var total = 0L
        for (folder in all) {
            val size = File(folder, "before.zip").length()
            if (retained < MAX_RETAINED && total + size <= MAX_HISTORY_BYTES) {
                retained++
                total += size
            } else if (folder.lastModified() < System.currentTimeMillis() - PENDING_GRACE_MS ||
                try { Json.decodeFromString<CheckpointRecord>(File(folder, "checkpoint.json").readText()).complete }
                catch (_: Exception) { false }) {
                removeTree(folder)
            }
        }
    }

    private fun removeTree(root: File) {
        if (!root.exists()) return
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

    companion object {
        private const val MAX_ENTRIES = 50_000
        private const val MAX_CONTENT_BYTES = 500L * 1024 * 1024
        private const val MAX_HISTORY_BYTES = 500L * 1024 * 1024
        private const val MAX_RETAINED = 3
        private const val PENDING_GRACE_MS = 7L * 24 * 60 * 60 * 1000

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).toHex()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
