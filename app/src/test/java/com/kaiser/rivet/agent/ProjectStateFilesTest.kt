package com.kaiser.rivet.agent

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TestDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProjectStateFilesTest {
    private lateinit var provider: TestDocumentsProvider
    private lateinit var workspace: SafWorkspace
    private val tree = DocumentsContract.buildTreeDocumentUri("com.kaiser.rivet.testdocs", "root")
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Before fun setup() {
        val info = ProviderInfo().apply {
            authority = tree.authority
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info).get()
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        resolver.takePersistableUriPermission(tree, flags)
        workspace = SafWorkspace(resolver, tree)
    }

    @Test fun goalAndMemorySurviveANewStoreAndExternalEditsAreSeen() = runBlocking {
        val files = ProjectStateFiles(workspace)
        val goal = GoalDocument.newGoal("ship login", "s1").withProgress("build green", "s1")
        files.saveGoal(goal)
        files.saveMemory(MemoryFormat.render(listOf("stack: Kotlin", "gotcha: generated/ is off limits")))

        val reloaded = ProjectStateFiles(workspace)
        assertEquals(goal, reloaded.loadGoal())
        assertEquals(2, MemoryFormat.lineCount(reloaded.loadMemory()))

        // A user editing the file outside Rivet is the next read, not a cached value.
        val edited = MemoryFormat.render(listOf("stack: Kotlin", "gotcha: generated/ is off limits",
            "convention: use spaces"))
        reloaded.saveMemory(edited)
        assertEquals(3, MemoryFormat.lineCount(ProjectStateFiles(workspace).loadMemory()))
    }

    @Test fun runJournalPersistsUnderRivetAndRecoversInterrupted() = runBlocking {
        val files = ProjectStateFiles(workspace)
        val journal = RunJournal(ProjectRunStorage(files))
        var run = RunMachine.begin("run-1", workspace.tree.toString(), "Build the feature", now = 1)
        run = RunMachine.start(run, now = 2)
        run = RunMachine.step(run, "read the fixture", now = 3)
        journal.save(run)

        val recovered = RunJournal(ProjectRunStorage(ProjectStateFiles(workspace))).recoverLatest()
        assertNotNull(recovered)
        assertEquals(RunPauseReason.Interrupted, recovered!!.pause)
        assertEquals(1, recovered.lastCompletedStep)
        assertTrue(ProjectStateFiles(workspace).read(".rivet/runs/run-1/run.json").orEmpty()
            .contains("run-1"))
    }

    @Test fun memoryRefusalAndForgetDoNotTouchTheOriginalFile() = runBlocking {
        val files = ProjectStateFiles(workspace)
        files.saveMemory(MemoryFormat.render(listOf("stack: Kotlin", "gotcha: one")))
        val before = files.loadMemory()
        val refused = MemoryFormat.append(before, "password = supersecret123")
        assertTrue(refused is MemoryFormat.Append.Refused)
        assertEquals(before, files.loadMemory())
        val forgotten = MemoryFormat.forget(before, 1)
        assertFalse(MemoryFormat.facts(forgotten).contains("gotcha: one"))
    }
}
