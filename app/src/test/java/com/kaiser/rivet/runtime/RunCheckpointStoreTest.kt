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

    @Test fun stageRestoreHandlesNewDirectoriesAndFileDirectorySwaps() = runBlocking {
        val project = temp.newFolder("swaps")
        project.resolve("wasFile").writeText("old file")
        project.resolve("wasEmptyDir").mkdirs()
        val store = RunCheckpointStore(temp.newFolder("swaps-store"), "ws")
        val id = store.begin(project, "run-swap", 0, "Before swap")

        project.resolve("wasFile").delete()
        project.resolve("wasFile/nested").mkdirs()
        project.resolve("wasFile/nested/a.txt").writeText("inside new dir")
        project.resolve("wasEmptyDir").delete()
        project.resolve("wasEmptyDir").writeText("now a file")
        project.resolve("addedDir/sub").mkdirs()
        project.resolve("addedDir/sub/new.txt").writeText("created")
        assertTrue(store.finish(id, project))

        val restore = store.stageRestore(store.history().single(), project)
        assertTrue(restore.conflicts.isEmpty())
        assertTrue(File(restore.stage, "wasFile").isFile)
        assertEquals("old file", File(restore.stage, "wasFile").readText())
        assertTrue(File(restore.stage, "wasEmptyDir").isDirectory)
        assertEquals(emptyList<String>(), File(restore.stage, "wasEmptyDir").list()?.toList() ?: emptyList())
        assertFalse(File(restore.stage, "addedDir").exists())
    }

    @Test fun gitCheckpointRestoresModifiedGitignoredFilesViaDeltaFallback() = runBlocking {
        val project = gitProject()
        project.resolve(".gitignore").writeText("*.secret\n")
        project.resolve("local.secret").writeText("before-secret")
        Git.open(project).use { git ->
            git.add().addFilepattern(".gitignore").call()
            git.commit().setMessage("ignore secrets").setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com").call()
        }
        val store = RunCheckpointStore(temp.newFolder("git-ignored-store"), "ws")
        val id = store.begin(project, "run-ignored", 0, "Before secret edit")
        project.resolve("local.secret").writeText("after-secret")
        assertTrue(store.finish(id, project))

        val record = store.history().single()
        assertNotNull(record.gitRef)
        val restore = store.stageRestore(record, project)
        assertTrue(restore.conflicts.isEmpty())
        assertEquals("before-secret", File(restore.stage, "local.secret").readText())
    }

    @Test fun gitCheckpointPreservesUserStagedIndexEntries() = runBlocking {
        val project = gitProject()
        project.resolve("staged.txt").writeText("staged content")
        project.resolve("unstaged.txt").writeText("before")
        Git.open(project).use { git ->
            git.add().addFilepattern("staged.txt").call()
        }

        val store = RunCheckpointStore(temp.newFolder("git-staged-store"), "ws")
        val id = store.begin(project, "run-staged", 0, "Edit unstaged")
        project.resolve("unstaged.txt").writeText("after")
        assertTrue(store.finish(id, project))

        Git.open(project).use { git ->
            val status = git.status().call()
            assertTrue("staged.txt should remain in index added", "staged.txt" in status.added)
        }
    }

    @Test fun stageRestoreRunUndoesAllStepsOfMultiStepRunUsingLatestAfterAndEarliestBefore() = runBlocking {
        val project = temp.newFolder("multi-step-run")
        project.resolve("shared.txt").writeText("v0")
        project.resolve("second.txt").writeText("untouched-initially")
        val store = RunCheckpointStore(temp.newFolder("multi-step-store"), "ws")

        val step0 = store.begin(project, "run-multi", 0, "Edit shared")
        project.resolve("shared.txt").writeText("v1")
        assertTrue(store.finish(step0, project))

        val step1 = store.begin(project, "run-multi", 1, "Edit shared again and second")
        project.resolve("shared.txt").writeText("v2")
        project.resolve("second.txt").writeText("changed-in-step1")
        assertTrue(store.finish(step1, project))

        val restore = store.stageRestoreRun(store.forRun("run-multi"), project)
        assertTrue(restore.conflicts.isEmpty())
        assertEquals("v0", File(restore.stage, "shared.txt").readText())
        assertEquals("untouched-initially", File(restore.stage, "second.txt").readText())
    }
}
