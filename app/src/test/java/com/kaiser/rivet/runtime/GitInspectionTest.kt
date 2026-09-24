package com.kaiser.rivet.runtime

import java.io.File
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitInspectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun nonGitWorkspaceIsValid() = runTest {
        val inspector = GitInspection(temp.newFolder())
        assertFalse(inspector.status().present)
        assertFalse(inspector.diff().present)
    }

    @Test fun statusAndDiffReadUserRepositoryWithoutChangingIndexOrHistory() = runTest {
        val root = temp.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            File(root, "tracked.txt").writeText("first\n")
            git.add().addFilepattern("tracked.txt").call()
            git.commit().setMessage("initial").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call()
            File(root, "tracked.txt").writeText("second\n")
            File(root, "staged.txt").writeText("staged\n")
            git.add().addFilepattern("staged.txt").call()
            File(root, "new.txt").writeText("untracked\n")
            val indexBefore = File(root, ".git/index").readBytes()
            val headBefore = git.repository.resolve("HEAD")!!.name

            val inspector = GitInspection(root)
            val status = inspector.status()
            val diff = inspector.diff("tracked.txt")

            assertTrue(status.present)
            assertEquals(headBefore, status.head)
            assertTrue(status.branch!!.isNotBlank())
            assertTrue("staged.txt" in status.staged)
            assertTrue("tracked.txt" in status.modified)
            assertTrue("new.txt" in status.untracked)
            assertTrue(diff.present)
            assertTrue("second" in diff.text)
            assertFalse("staged.txt" in diff.text)
            assertTrue(indexBefore.contentEquals(File(root, ".git/index").readBytes()))
            assertEquals(headBefore, git.repository.resolve("HEAD")!!.name)
        }
    }

    @Test fun largeDiffIsBounded() = runTest {
        val root = temp.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            File(root, "large.txt").writeText("old\n")
            git.add().addFilepattern("large.txt").call()
            git.commit().setMessage("initial").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call()
            File(root, "large.txt").writeText(("changed line\n").repeat(30_000))

            val diff = GitInspection(root).diff("large.txt")
            assertTrue(diff.present)
            assertTrue(diff.text.toByteArray(Charsets.UTF_8).size <= GitInspection.DIFF_BYTES)
            assertTrue(diff.limited || "Binary files differ" in diff.text)
        }
    }
}
