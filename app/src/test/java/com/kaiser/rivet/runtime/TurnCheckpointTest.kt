package com.kaiser.rivet.runtime

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TurnCheckpointTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun noChangeLeavesNoVisibleCheckpoint() = runTest {
        val root = temp.newFolder("worktree")
        File(root, "file.txt").writeText("original")
        val store = TurnCheckpoint(temp.newFolder("private-checkpoints"), "workspace-A")

        val id = store.begin(root)
        assertFalse(store.finish(id, root))
        assertEquals(null, store.latest())
    }

    @Test fun binaryCreateDeleteAndEditRestoreExactlyWithoutGitMetadata() = runTest {
        val root = temp.newFolder("worktree")
        val privateStorage = temp.newFolder("private-checkpoints")
        val original = byteArrayOf(0, 1, 2, 0xFF.toByte(), 10)
        File(root, "old.bin").writeBytes(original)
        File(root, "delete.txt").writeText("keep me")
        File(root, ".git").mkdir()
        File(root, ".git/index").writeText("user index")
        val store = TurnCheckpoint(privateStorage, "workspace-A")
        val id = store.begin(root)
        File(root, "old.bin").writeBytes(byteArrayOf(7, 8))
        File(root, "delete.txt").delete()
        File(root, "new.kt").writeText("val n = 1\n")
        assertTrue(store.finish(id, root))

        val record = store.latest()
        assertNotNull(record)
        val changes = store.changes(record!!)
        assertEquals(setOf("old.bin", "delete.txt", "new.kt"), changes.map { it.path }.toSet())
        val staged = store.stageUndo(record, root)
        assertArrayEquals(original, File(staged, "old.bin").readBytes())
        assertEquals("keep me", File(staged, "delete.txt").readText())
        assertFalse(File(staged, "new.kt").exists())
        assertEquals("user index", File(staged, ".git/index").readText())
        assertFalse(record.before.containsKey(".git/index"))
        assertTrue(staged.canonicalPath.startsWith(privateStorage.canonicalPath))

        store.markUndone(id)
        assertEquals(null, store.latest())
    }

    @Test fun newerEditRefusesUndoWithoutChangingEitherCopy() = runTest {
        val root = temp.newFolder("worktree")
        val target = File(root, "file.txt")
        target.writeText("before")
        val store = TurnCheckpoint(temp.newFolder("private-checkpoints"), "workspace-A")
        val id = store.begin(root)
        target.writeText("agent")
        store.finish(id, root)
        val record = store.latest()!!
        target.writeText("external")

        assertEquals(listOf("file.txt"), store.changes(record).map { it.path })

        try { store.stageUndo(record, root); throw AssertionError("Expected conflict") }
        catch (error: CheckpointFailure) { assertEquals("undo_conflict", error.code) }
        assertEquals("external", target.readText())
    }

    @Test fun refusedInstallCanDiscardOnlyTheDisposableRestoreStage() = runTest {
        val root = temp.newFolder("worktree")
        val original = File(root, "file.txt")
        original.writeText("before")
        val store = TurnCheckpoint(temp.newFolder("private-checkpoints"), "workspace-A")
        val id = store.begin(root)
        original.writeText("after")
        store.finish(id, root)
        val record = store.latest()!!
        val staged = store.stageUndo(record, root)

        store.discardStagedUndo(id)

        assertFalse(staged.exists())
        assertEquals("after", original.readText())
        assertNotNull(store.latest())
        assertEquals("before", File(store.stageUndo(record, root), "file.txt").readText())
    }

    @Test fun newerEditBeforeTurnEndsDoesNotBecomeUndoableAgentWork() = runTest {
        val root = temp.newFolder("worktree")
        val target = File(root, "file.txt")
        target.writeText("before")
        val store = TurnCheckpoint(temp.newFolder("private-checkpoints"), "workspace-A")
        val id = store.begin(root)
        target.writeText("agent")
        store.recordPost(id, root)
        assertTrue(store.matchesPost(id, root))
        target.writeText("external")
        assertFalse(store.matchesPost(id, root))
        try { store.finish(id, root); throw AssertionError("Expected conflict") }
        catch (error: CheckpointFailure) { assertEquals("undo_conflict", error.code) }
        assertEquals(null, store.latest())
        assertEquals("external", target.readText())
    }

    @Test fun checkpointsAreSeparatedByWorkspaceIdentity() = runTest {
        val root = temp.newFolder("worktree")
        val storage = temp.newFolder("private-checkpoints")
        File(root, "a").writeText("a")
        val first = TurnCheckpoint(storage, "workspace-A")
        val second = TurnCheckpoint(storage, "workspace-B")
        val id = first.begin(root)
        File(root, "a").writeText("b")
        first.finish(id, root)

        assertNotNull(first.latest())
        assertEquals(null, second.latest())
    }
}
