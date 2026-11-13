package com.kaiser.rivet.runtime

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk

@Serializable
data class RunCheckpointInfo(
    val id: String,
    val runId: String,
    val workspace: String,
    val createdAt: Long,
    val sequence: Int,
    val summary: String,
    val gitRef: String? = null,
    val before: Map<String, CheckpointEntry>,
    val after: Map<String, CheckpointEntry>? = null,
    val delta: List<String> = emptyList(),
)

data class RunCheckpointRestore(
    val stage: File,
    val restored: List<String>,
    val conflicts: List<String>,
)

/**
 * Run checkpoints are separate from the per-turn Undo archive. Git projects get a
 * commit on a hidden ref; non-git projects keep only the pre-change copies of files
 * that actually changed. Metadata lives in app storage, never in the user's history.
 */
class RunCheckpointStore(private val root: File, private val workspace: String,
                         private val maxBytes: Long = 500L * 1024 * 1024) {
    private val directory = File(root, sha256(workspace.toByteArray()))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastPruned: Int = try {
        File(directory, "pruned").readText().toInt()
    } catch (_: Exception) { 0 }
        private set

    suspend fun begin(worktree: File, runId: String, sequence: Int, summary: String): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val folder = File(directory, id)
            if (!folder.mkdirs()) throw CheckpointFailure("storage")
            val before = scan(worktree)
            val gitRef = if (isGit(worktree)) {
                try { GitCheckpoint.commit(worktree, ref(runId, sequence), "Rivet checkpoint $runId/$sequence") }
                catch (_: Exception) { null }
            } else null
            val deltaPaths = if (gitRef == null) {
                before.keys
            } else {
                GitCheckpoint.missingPaths(
                    worktree,
                    gitRef,
                    before.filterValues { !it.directory }.keys,
                )
            }
            val delta = copyBefore(worktree, folder, before.filterKeys { it in deltaPaths })
            if (folderSize(folder) > maxBytes) {
                removeTree(folder)
                throw CheckpointFailure("storage")
            }
            write(folder, RunCheckpointInfo(id, runId, workspace, System.currentTimeMillis(), sequence,
                summary.take(200), gitRef, before, delta = delta))
            id
        }

    suspend fun finish(id: String, worktree: File): Boolean = withContext(Dispatchers.IO) {
        val folder = folder(id)
        val record = read(folder)
        if (record.after != null) return@withContext record.before != record.after
        val after = scan(worktree)
        if (record.before == after) {
            removeTree(folder)
            return@withContext false
        }
        val changed = changedPaths(record.before, after)
        val delta = retainOnly(folder, changed)
        write(folder, record.copy(after = after, delta = delta))
        prune()
        true
    }

    suspend fun history(limit: Int = 100): List<RunCheckpointInfo> = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { try { read(it) } catch (_: Exception) { null } }
            .filter { it.after != null }
            .sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, 500))
    }

    suspend fun latestForRun(runId: String): RunCheckpointInfo? = withContext(Dispatchers.IO) {
        history().firstOrNull { it.runId == runId }
    }

    /** Pre-change bytes for non-git delta copies; null when the file was absent or git-backed. */
    fun deltaBytes(record: RunCheckpointInfo, path: String): ByteArray? {
        if (record.gitRef != null) return null
        val source = File(folder(record.id), "delta/$path")
        return if (source.isFile) source.readBytes() else null
    }

    /**
     * Builds a full replacement worktree for the mirror installer. Only paths Rivet
     * changed are touched; an external change on one of them is a conflict, never an
     * overwrite.
     */
    suspend fun stageRestore(record: RunCheckpointInfo, worktree: File): RunCheckpointRestore =
        withContext(Dispatchers.IO) {
            val after = record.after ?: throw CheckpointFailure("undo_unavailable")
            val current = scan(worktree)
            val changed = changedPaths(record.before, after)
            val conflicts = changed.filter { current[it] != after[it] }
            if (conflicts.isNotEmpty()) return@withContext RunCheckpointRestore(File(""), emptyList(), conflicts)
            val stage = File(folder(record.id), "restore-worktree")
            if (stage.exists()) removeTree(stage)
            copyTree(worktree, stage)
            for (path in changed.sortedDescending()) {
                val target = File(stage, path)
                val before = record.before[path]
                when {
                    before == null -> removeTree(target)
                    before.directory -> {
                        if (target.exists() && !target.isDirectory) removeTree(target)
                        target.mkdirs()
                    }
                    else -> if (target.isDirectory) removeTree(target)
                }
            }
            for (path in changed) {
                val before = record.before[path] ?: continue
                if (before.directory) continue
                val target = File(stage, path)
                val bytes = readBefore(record, path, worktree) ?: throw CheckpointFailure("corrupt")
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { it.write(bytes) }
            }
            RunCheckpointRestore(stage, changed, emptyList())
        }

    fun discard(record: RunCheckpointInfo) {
        removeTree(File(folder(record.id), "restore-worktree"))
    }

    fun markRestored(record: RunCheckpointInfo) {
        write(folder(record.id), record.copy(summary = "${record.summary} · restored"))
    }

    private fun readBefore(record: RunCheckpointInfo, path: String, worktree: File): ByteArray? {
        val ref = record.gitRef
        if (ref != null) {
            GitCheckpoint.read(worktree, ref, path)?.let { return it }
        }
        val source = File(folder(record.id), "delta/$path")
        return if (source.isFile) source.readBytes() else null
    }

    private fun copyBefore(worktree: File, folder: File, before: Map<String, CheckpointEntry>): List<String> {
        val target = File(folder, "delta")
        before.forEach { (path, entry) ->
            if (!entry.directory) {
                val source = File(worktree, path)
                val destination = File(target, path)
                destination.parentFile?.mkdirs()
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return before.filterValues { !it.directory }.keys.toList()
    }

    private fun retainOnly(folder: File, changed: List<String>): List<String> {
        val delta = File(folder, "delta")
        if (!delta.exists()) return emptyList()
        val wanted = changed.toSet()
        val retained = mutableListOf<String>()
        Files.walkFileTree(delta.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = delta.toPath().relativize(file).joinToString("/")
                if (relative !in wanted) {
                    Files.delete(file)
                } else {
                    retained.add(relative)
                }
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (dir != delta.toPath() && dir.toFile().list().isNullOrEmpty()) Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return retained
    }

    private fun prune() {
        val records = directory.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { try { read(it) to it } catch (_: Exception) { null } }
            .sortedByDescending { it.first.createdAt }
        var total = 0L
        var removed = 0
        for ((record, folder) in records) {
            val size = folderSize(folder)
            if (total + size <= maxBytes) {
                total += size
            } else {
                removeTree(folder)
                removed++
            }
        }
        lastPruned += removed
        try { File(directory, "pruned").writeText(lastPruned.toString()) } catch (_: Exception) { }
    }

    private fun folder(id: String): File {
        if (!Regex("[0-9a-fA-F-]{36}").matches(id)) throw CheckpointFailure("invalid_checkpoint")
        return File(directory, id)
    }

    private fun read(folder: File): RunCheckpointInfo {
        val value = json.decodeFromString<RunCheckpointInfo>(File(folder, "checkpoint.json").readText())
        if (value.workspace != workspace || value.id != folder.name) throw CheckpointFailure("corrupt")
        return value
    }

    private fun write(folder: File, value: RunCheckpointInfo) {
        val next = File(folder, "checkpoint.next")
        FileOutputStream(next).use { output ->
            output.write(json.encodeToString(RunCheckpointInfo.serializer(), value).toByteArray())
            output.fd.sync()
        }
        val target = File(folder, "checkpoint.json").toPath()
        try {
            Files.move(next.toPath(), target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(next.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun scan(root: File): Map<String, CheckpointEntry> {
        val entries = linkedMapOf<String, CheckpointEntry>()
        if (!root.isDirectory) return entries
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val path = relative(root, dir)
                if (path == ".git" || path == ".rivet/runs" || path == ".rivet/checkpoints" ||
                    (path.isNotEmpty() && ignored(root, path))) return FileVisitResult.SKIP_SUBTREE
                if (path.isNotEmpty()) entries[path] = CheckpointEntry(true)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                val path = relative(root, file)
                if (ignored(root, path)) return FileVisitResult.CONTINUE
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file.toFile()).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                entries[path] = CheckpointEntry(false, attrs.size(), digest.digest().toHex())
                return FileVisitResult.CONTINUE
            }
        })
        return entries
    }

    private fun ignored(root: File, path: String): Boolean {
        val first = path.substringBefore('/')
        val ignored = listOf("build", "node_modules", ".gradle", "dist", "target", "__pycache__")
        return first in ignored
    }

    private fun changedPaths(before: Map<String, CheckpointEntry>, after: Map<String, CheckpointEntry>): List<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.sorted()

    private fun copyTree(source: File, target: File) {
        if (target.exists()) removeTree(target)
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(target.toPath().resolve(source.toPath().relativize(dir)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                Files.copy(file, target.toPath().resolve(source.toPath().relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun removeTree(root: File) {
        if (!root.exists()) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }

    private fun folderSize(folder: File): Long = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun relative(root: File, path: Path): String =
        root.toPath().relativize(path).joinToString("/") { it.toString() }

    private fun isGit(worktree: File) = File(worktree, ".git").isDirectory

    private fun ref(runId: String, sequence: Int): String =
        "refs/rivet/checkpoints/${runId.filter { it.isLetterOrDigit() || it == '-' }}/$sequence"

    companion object {
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }
}

private object GitCheckpoint {
    fun commit(worktree: File, ref: String, message: String): String {
        val repo = FileRepositoryBuilder().setGitDir(File(worktree, ".git"))
            .setWorkTree(worktree).build()
        repo.use {
            val branch = it.fullBranch
            val original = it.exactRef(branch)?.objectId
            if (original == null) throw CheckpointFailure("no_commit")
            val cache = DirCache.newInCore()
            val builder = cache.builder()
            it.newObjectInserter().use { inserter ->
                TreeWalk(it).use { walk ->
                    walk.addTree(FileTreeIterator(it))
                    walk.isRecursive = false
                    while (walk.next()) {
                        val path = walk.pathString
                        if (path == ".git" || path.startsWith(".git/")) continue
                        val working = walk.getTree(0, FileTreeIterator::class.java)
                        if (working.isEntryIgnored) continue
                        if (walk.isSubtree) {
                            walk.enterSubtree()
                            continue
                        }
                        val mode = working.entryFileMode
                        if (mode == FileMode.TREE || mode == FileMode.GITLINK) continue
                        val entry = DirCacheEntry(walk.rawPath).apply {
                            fileMode = mode
                            setObjectId(
                                working.openEntryStream().use { stream ->
                                    inserter.insert(Constants.OBJ_BLOB, working.entryLength, stream)
                                },
                            )
                        }
                        builder.add(entry)
                    }
                }
                builder.finish()
                val treeId = cache.writeTree(inserter)
                val who = PersonIdent("Rivet", "rivet@local")
                val commitId = inserter.insert(CommitBuilder().apply {
                    setTreeId(treeId)
                    setParentId(original)
                    author = who
                    committer = who
                    this.message = message
                })
                inserter.flush()
                it.updateRef(ref).apply {
                    setNewObjectId(commitId)
                    setForceUpdate(true)
                }.forceUpdate()
                return commitId.name
            }
        }
    }

    fun missingPaths(worktree: File, ref: String, paths: Collection<String>): Set<String> {
        val repo = FileRepositoryBuilder().setGitDir(File(worktree, ".git"))
            .setWorkTree(worktree).build()
        repo.use {
            val missing = hashSetOf<String>()
            for (path in paths) {
                if (it.resolve("$ref:$path") == null) missing.add(path)
            }
            return missing
        }
    }

    fun read(worktree: File, ref: String, path: String): ByteArray? {
        val repo = FileRepositoryBuilder().setGitDir(File(worktree, ".git"))
            .setWorkTree(worktree).build()
        repo.use {
            val id = it.resolve("$ref:$path") ?: return null
            return it.open(id, Constants.OBJ_BLOB).bytes
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
