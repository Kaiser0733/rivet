package com.kaiser.rivet.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeVcsCarryTest {
    @Test fun aRebuildKeepsTheGitMetadataAndHead() {
        val base = Files.createTempDirectory("rivet-vcs-carry").toFile()
        try {
            val worktree = File(base, "worktree").apply { mkdirs() }
            val git = File(worktree, ".git").apply { mkdirs() }
            val head = File(git, "HEAD").apply { writeText("ref: refs/heads/main\n") }
            val carry = WorktreeVcsCarry(File(base, "carry"))

            assertEquals(1, carry.take(worktree))
            worktree.deleteRecursively()
            worktree.mkdirs()
            assertEquals(1, carry.restore(worktree))
            assertEquals("ref: refs/heads/main\n", File(worktree, ".git/HEAD").readText())
            assertEquals(head.readText(), File(worktree, ".git/HEAD").readText())
        } finally { base.deleteRecursively() }
    }

    @Test fun aCheckpointWithoutGitCannotDeleteTheExistingRepo() {
        val base = Files.createTempDirectory("rivet-vcs-checkpoint").toFile()
        try {
            val worktree = File(base, "worktree").apply { mkdirs() }
            File(worktree, ".git").mkdirs()
            File(worktree, ".git/HEAD").writeText("ref: refs/heads/main\n")
            val carry = WorktreeVcsCarry(File(base, "carry"))
            carry.take(worktree)
            // The replacement tree is built from a pre-repo checkpoint: no .git.
            worktree.deleteRecursively()
            worktree.mkdirs()
            carry.restore(worktree)
            assertTrue(File(worktree, ".git/HEAD").isFile)
        } finally { base.deleteRecursively() }
    }

    @Test fun aStaleCarryDoesNotOutliveANewTreeThatAlreadyHasVcs() {
        val base = Files.createTempDirectory("rivet-vcs-stale").toFile()
        try {
            val worktree = File(base, "worktree").apply { mkdirs() }
            File(worktree, ".git").mkdirs()
            File(worktree, ".git/HEAD").writeText("old")
            val carry = WorktreeVcsCarry(File(base, "carry"))
            carry.take(worktree)
            File(worktree, ".git").mkdirs()
            File(worktree, ".git/HEAD").writeText("new")
            carry.restore(worktree)
            assertEquals("new", File(worktree, ".git/HEAD").readText())
            assertFalse(File(base, "carry/.git").exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun aWorktreeWithoutVcsHasNoNote() {
        val base = Files.createTempDirectory("rivet-vcs-note").toFile()
        try {
            assertFalse(File(base, ".git").exists())
            assertEquals(null, worktreeVcsNote(base))
            File(base, ".git").mkdirs()
            assertTrue(worktreeVcsNote(base)!!.contains("worktree-local"))
        } finally { base.deleteRecursively() }
    }
}
