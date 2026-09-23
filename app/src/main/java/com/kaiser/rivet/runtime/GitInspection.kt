package com.kaiser.rivet.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.filter.PathFilter

data class RepositoryStatus(
    val present: Boolean,
    val branch: String? = null,
    val head: String? = null,
    val staged: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    val untracked: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

data class RepositoryDiff(val present: Boolean, val text: String, val limited: Boolean, val files: Int)

/** Inspects only a repository rooted at the selected worktree. Gitfile links can escape it. */
class GitInspection(private val worktree: File) {
    private fun open(): Repository? {
        val gitDir = File(worktree, ".git")
        if (!gitDir.isDirectory || Files.isSymbolicLink(gitDir.toPath())) return null
        val repo = FileRepositoryBuilder().setGitDir(gitDir).setWorkTree(worktree).build()
        if (!repo.objectDatabase.exists()) {
            repo.close()
            return null
        }
        return repo
    }

    suspend fun status(): RepositoryStatus = withContext(Dispatchers.IO) {
        val repo = open() ?: return@withContext RepositoryStatus(false)
        repo.use { repository ->
            Git(repository).use { git ->
                val state = git.status().call()
                RepositoryStatus(
                    present = true,
                    branch = repository.branch,
                    head = repository.exactRef(Constants.HEAD)?.objectId?.name,
                    staged = (state.added + state.changed + state.removed).sorted(),
                    modified = state.modified.sorted(),
                    deleted = state.missing.sorted(),
                    untracked = state.untracked.sorted(),
                    conflicts = state.conflicting.sorted(),
                )
            }
        }
    }

    suspend fun diff(path: String = ""): RepositoryDiff = withContext(Dispatchers.IO) {
        val repo = open() ?: return@withContext RepositoryDiff(false, "", false, 0)
        repo.use { repository ->
            Git(repository).use { git ->
                val output = LimitedDiffOutput(DIFF_BYTES)
                var files = 0
                var limited = false
                DiffFormatter(output).use { formatter ->
                    formatter.setRepository(repository)
                    formatter.setContext(3)
                    formatter.setBinaryFileThreshold(128 * 1024)
                    for (cached in listOf(true, false)) {
                        val command = git.diff().setCached(cached)
                        if (path.isNotEmpty()) command.setPathFilter(PathFilter.create(path))
                        val entries = command.call()
                        for (entry in entries) {
                            if (files == MAX_DIFF_FILES) {
                                limited = true
                                break
                            }
                            try {
                                output.write((if (cached) "Staged\n" else "Unstaged\n").toByteArray())
                                formatter.format(entry)
                                files++
                            } catch (_: DiffLimit) {
                                limited = true
                                break
                            }
                        }
                        if (limited) break
                    }
                }
                RepositoryDiff(true, output.toString(Charsets.UTF_8.name()), limited, files)
            }
        }
    }

    private class DiffLimit : IOException()

    private class LimitedDiffOutput(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val room = limit - count
            if (length > room) {
                if (room > 0) super.write(bytes, offset, room)
                throw DiffLimit()
            }
            super.write(bytes, offset, length)
        }

        override fun write(value: Int) {
            if (count == limit) throw DiffLimit()
            super.write(value)
        }
    }

    companion object {
        const val DIFF_BYTES = 16 * 1024
        const val MAX_DIFF_FILES = 20
    }
}
