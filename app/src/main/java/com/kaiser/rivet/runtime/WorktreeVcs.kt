package com.kaiser.rivet.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

// A project that becomes a git repository must not mirror its object database
// through SAF. It is large, changes constantly, and has no user-visible copy value.
// It is worktree-local state: never mirrored, but never lost when the private
// worktree is rebuilt or swapped by a checkpoint restore.
internal val VCS_DIRECTORIES = setOf(".git", ".hg", ".svn", ".jj", "_darcs")

internal fun isVcsPath(path: String): Boolean =
    path.split('/').any { it in VCS_DIRECTORIES }

internal fun worktreeVcsNote(worktree: File): String? =
    if (VCS_DIRECTORIES.any { name ->
            Files.isDirectory(File(worktree, name).toPath(), LinkOption.NOFOLLOW_LINKS)
        }) "Repository metadata is worktree-local and preserved; it is not mirrored to SAF."
    else null

/** Moves top-level VCS directories aside before a rebuild and back after it. */
internal class WorktreeVcsCarry(private val carry: File) {
    fun take(worktree: File): Int {
        if (!carry.isDirectory && !carry.mkdirs()) throw IllegalStateException("vcs_carry_storage")
        var moved = 0
        for (name in VCS_DIRECTORIES) {
            val source = File(worktree, name)
            if (!Files.isDirectory(source.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            val target = File(carry, name)
            if (target.exists()) throw IllegalStateException("vcs_carry_collision")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            moved++
        }
        return moved
    }

    fun restore(worktree: File): Int {
        if (!carry.isDirectory) return 0
        var moved = 0
        for (name in VCS_DIRECTORIES) {
            val source = File(carry, name)
            if (!Files.isDirectory(source.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            val target = File(worktree, name)
            if (target.exists()) {
                removeTree(source)
                continue
            }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            moved++
        }
        return moved
    }

    private fun removeTree(root: File) {
        Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
