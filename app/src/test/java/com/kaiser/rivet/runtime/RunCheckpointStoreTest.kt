package com.kaiser.rivet.runtime

import java.io.File
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunCheckpointStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun gitProject(): File {
        val root = temp.newFolder("repo")
        Git.init().setDirectory(root).call().use { git ->
            root.resolve("a.txt").writeText("one")
            git.add().addFilepattern("a.txt").call()
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com").call()
        }
        return root
    }

    @Test fun gitCheckpointUsesHiddenRefAndRestoresChangedFiles() = runBlocking {
        val project = gitProject()
        val headBefore = Git.open(project).use { it.repository.resolve("HEAD") }
        val store = RunCheckpointStore(temp.newFolder("store"), "ws")
        val id = store.begin(project, "run-1", 0, "Before a change")
        project.resolve("a.txt").writeText("two")
        assertTrue(store.finish(id, project))

        val record = store.history().single()
        assertNotNull(record.gitRef)
        Git.open(project).use { assertEquals(headBefore, it.repository.resolve("HEAD")) }
        Git.open(project).use { git ->
            assertNotNull(git.repository.exactRef("refs/rivet/checkpoints/run-1/0"))
        }
        project.resolve("a.txt").writeText("external")
        assertEquals(listOf("a.txt"), store.stageRestore(record, project).conflicts)

        project.resolve("a.txt").writeText("two")
        val restore = store.stageRestore(record, project)
        assertTrue(restore.conflicts.isEmpty())
        assertEquals("one", File(restore.stage, "a.txt").readText())
    }

    @Test fun deltaCheckpointRestoresBytesAndListsExternalConflicts() = runBlocking {
        val project = temp.newFolder("plain")
        project.resolve("a.txt").writeText("before")
        project.resolve("b.txt").writeText("same")
        val store = RunCheckpointStore(temp.newFolder("delta-store"), "ws")
        val id = store.begin(project, "run-2", 0, "Before edit")
        project.resolve("a.txt").writeText("after")
        assertTrue(store.finish(id, project))
        val record = store.history().single()
        assertTrue(record.gitRef == null)
        assertTrue(record.delta.contains("a.txt"))
        assertFalse(record.delta.contains("b.txt"))

        project.resolve("a.txt").writeText("external")
        val conflict = store.stageRestore(record, project)
        assertEquals(listOf("a.txt"), conflict.conflicts)

        project.resolve("a.txt").writeText("after")
        val clean = store.stageRestore(record, project)
        assertEquals("before", File(clean.stage, "a.txt").readText())
        assertEquals("same", File(clean.stage, "b.txt").readText())
    }

    @Test fun pruningReportsWhatItRemoved() = runBlocking {
        val project = temp.newFolder("prune")
        val store = RunCheckpointStore(temp.newFolder("prune-store"), "ws", maxBytes = 50)
        repeat(3) { index ->
            project.resolve("a.txt").writeText("v$index")
            val id = store.begin(project, "run-$index", 0, "checkpoint $index")
            project.resolve("a.txt").writeText("v${index + 1}")
            assertTrue(store.finish(id, project))
        }
        assertTrue(store.lastPruned > 0)
        assertTrue(store.history().size < 3)
    }
}
