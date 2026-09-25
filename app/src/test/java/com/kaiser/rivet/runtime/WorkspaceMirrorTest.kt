package com.kaiser.rivet.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TestDocumentsProvider
import com.kaiser.rivet.workspace.WorkspacePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorkspaceMirrorTest {
    private lateinit var provider: TestDocumentsProvider
    private lateinit var workspace: SafWorkspace
    private lateinit var app: Context
    private lateinit var files: File
    private val tree = DocumentsContract.buildTreeDocumentUri("com.kaiser.rivet.mirrortest", "root")

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        files = Files.createTempDirectory(base.cacheDir.toPath(), "mirror-fixture").toFile()
        app = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        val info = ProviderInfo().apply {
            authority = tree.authority
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info).get()
        val resolver = base.contentResolver
        resolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        workspace = SafWorkspace(resolver, tree)
    }

    @After fun cleanup() { files.deleteRecursively() }

    private fun path(value: String) = WorkspacePath.parse(value)
    private fun mirror() = WorkspaceMirror(app, workspace) { true }
    private suspend fun source(value: String, bytes: ByteArray) {
        val entry = workspace.createFile(path(value))
        provider.nodes[entry.documentId]!!.bytes.writeBytes(bytes)
    }
    private suspend fun bytes(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        workspace.copyFileTo(path(value), output)
        return output.toByteArray()
    }

    @Test fun materializesTextBinaryNestedUnicodeAndEmptyFiles() = runBlocking {
        workspace.createDirectory(path("src"))
        workspace.createDirectory(path("src/δ"))
        source("src/δ/name.ts", "const n = 'δ';\n".toByteArray())
        source("src/icon.png", byteArrayOf(0, 0xFF.toByte(), 0x80.toByte(), 1))
        source("empty", byteArrayOf())

        val ready = mirror().prepare()

        assertFalse(ready.dirty)
        assertArrayEquals("const n = 'δ';\n".toByteArray(), File(ready.worktree, "src/δ/name.ts").readBytes())
        assertArrayEquals(byteArrayOf(0, 0xFF.toByte(), 0x80.toByte(), 1), File(ready.worktree, "src/icon.png").readBytes())
        assertEquals(0, File(ready.worktree, "empty").length())
    }

    @Test fun createdModifiedAndDeletedMirrorFilesSyncToSaf() = runBlocking {
        workspace.createDirectory(path("src"))
        source("src/old.txt", "old".toByteArray())
        source("remove.txt", "remove".toByteArray())
        val mirror = mirror()
        val root = mirror.prepare().worktree
        File(root, "src/old.txt").writeText("new")
        File(root, "remove.txt").delete()
        File(root, "src/nested").mkdir()
        File(root, "src/nested/new.bin").writeBytes(byteArrayOf(0, 1, 2, 0xFF.toByte()))

        assertEquals(MirrorSync.Ok, mirror.sync().state)
        assertArrayEquals("new".toByteArray(), bytes("src/old.txt"))
        assertArrayEquals(byteArrayOf(0, 1, 2, 0xFF.toByte()), bytes("src/nested/new.bin"))
        assertFalse(workspace.listDirectory(WorkspacePath.ROOT).any { it.path.value == "remove.txt" })
        assertFalse(mirror.hasLocalChanges())
    }

    @Test fun checkpointUndoRestoresBinaryAndDeletedFilesThroughSafSync() = runBlocking {
        val original = byteArrayOf(0, 1, 2, 0xFF.toByte())
        source("asset.bin", original)
        source("delete.txt", "keep".toByteArray())
        val mirror = mirror()
        val root = mirror.prepare().worktree
        val store = TurnCheckpoint(File(files, "checkpoints"), tree.toString())
        val id = store.begin(root)
        File(root, "asset.bin").writeBytes(byteArrayOf(8, 9))
        File(root, "delete.txt").delete()
        assertEquals(MirrorSync.Ok, mirror.sync().state)
        assertTrue(store.finish(id, mirror.prepare().worktree))

        val stage = store.stageUndo(store.latest()!!, mirror.prepare().worktree)
        mirror.installCheckpoint(stage)
        assertEquals(MirrorSync.Ok, mirror.sync().state)
        assertArrayEquals(original, bytes("asset.bin"))
        assertArrayEquals("keep".toByteArray(), bytes("delete.txt"))
    }

    @Test fun checkpointUndoRefusesExternalEditAfterAgentTurn() = runBlocking {
        source("file.txt", "before".toByteArray())
        val mirror = mirror()
        val root = mirror.prepare().worktree
        val store = TurnCheckpoint(File(files, "checkpoints"), tree.toString())
        val id = store.begin(root)
        File(root, "file.txt").writeText("agent")
        assertEquals(MirrorSync.Ok, mirror.sync().state)
        store.finish(id, mirror.prepare().worktree)
        provider.nodes.values.first { it.name == "file.txt" }.bytes.writeText("external")

        try { store.stageUndo(store.latest()!!, mirror.prepare().worktree); fail("Expected conflict") }
        catch (error: CheckpointFailure) { assertEquals("undo_conflict", error.code) }
        assertArrayEquals("external".toByteArray(), bytes("file.txt"))
    }

    @Test fun externalChangeConflictsAndPreservesBothCopies() = runBlocking {
        source("file.txt", "base".toByteArray())
        val mirror = mirror()
        val local = File(mirror.prepare().worktree, "file.txt")
        local.writeText("mirror work")
        provider.nodes.values.first { it.name == "file.txt" }.bytes.writeText("outside work")

        val result = mirror.sync()

        assertEquals(MirrorSync.Conflict, result.state)
        assertEquals("file.txt", result.path)
        assertArrayEquals("outside work".toByteArray(), bytes("file.txt"))
        assertEquals("mirror work", local.readText())
        assertTrue(mirror.hasLocalChanges())
        assertEquals(MirrorSync.Conflict, mirror.sync().state)
        assertArrayEquals("outside work".toByteArray(), bytes("file.txt"))
        assertEquals("mirror work", local.readText())
    }

    @Test fun retryResumesAfterProviderFailureWithoutOverwritingExternalChanges() = runBlocking {
        source("a.txt", "before a".toByteArray())
        source("b.txt", "before b".toByteArray())
        val mirror = mirror()
        val root = mirror.prepare().worktree
        File(root, "a.txt").writeText("after a")
        File(root, "b.txt").writeText("after b")
        provider.rejectWriteOnceFor = "b.txt"

        assertEquals(MirrorSync.Failed, mirror.sync().state)
        assertEquals("after a", String(bytes("a.txt")))
        assertEquals("before b", String(bytes("b.txt")))

        assertEquals(MirrorSync.Ok, mirror.sync().state)
        assertEquals("after a", String(bytes("a.txt")))
        assertEquals("after b", String(bytes("b.txt")))
        assertFalse(mirror.hasLocalChanges())
    }

    @Test fun createdEmptySafFileResumesAfterWriteFailureAndProcessReconstruction() = runBlocking {
        val first = mirror()
        File(first.prepare().worktree, "new.txt").writeText("target content")
        provider.rejectWriteOnceFor = "new.txt"

        assertEquals(MirrorSync.Failed, first.sync().state)
        assertEquals(1, provider.createCalls)
        assertArrayEquals(byteArrayOf(), bytes("new.txt"))

        val restored = mirror()
        assertTrue(restored.prepare().dirty)
        assertEquals(MirrorSync.Ok, restored.sync().state)
        assertEquals(1, provider.createCalls)
        assertEquals("target content", String(bytes("new.txt")))
        assertFalse(restored.hasLocalChanges())
    }

    @Test fun partialCreateReceiptCannotOverwriteChangedOrReplacedSafFile() = runBlocking {
        val mirror = mirror()
        File(mirror.prepare().worktree, "new.txt").writeText("target content")
        provider.rejectWriteOnceFor = "new.txt"
        assertEquals(MirrorSync.Failed, mirror.sync().state)
        provider.nodes.values.first { it.name == "new.txt" }.bytes.writeText("external")

        assertEquals(MirrorSync.Conflict, mirror().sync().state)
        assertEquals("external", String(bytes("new.txt")))

        workspace.delete(path("new.txt"))
        workspace.createFile(path("new.txt"))
        assertEquals(MirrorSync.Conflict, mirror().sync().state)
        assertEquals(2, provider.createCalls)
    }

    @Test fun partialCreateReceiptRejectsChangedLocalTarget() = runBlocking {
        val mirror = mirror()
        val local = File(mirror.prepare().worktree, "new.txt")
        local.writeText("first target")
        provider.rejectWriteOnceFor = "new.txt"
        assertEquals(MirrorSync.Failed, mirror.sync().state)
        local.writeText("later target")

        assertEquals(MirrorSync.Conflict, mirror().sync().state)
        assertArrayEquals(byteArrayOf(), bytes("new.txt"))
        assertEquals(1, provider.createCalls)
    }

    @Test fun cleanMirrorRefreshesExternalChangesAndDirtyMirrorSurvivesRestart() = runBlocking {
        source("file.txt", "base".toByteArray())
        val mirror = mirror()
        val local = File(mirror.prepare().worktree, "file.txt")
        provider.nodes.values.first { it.name == "file.txt" }.bytes.writeText("outside")
        assertFalse(mirror.prepare().dirty)
        assertEquals("outside", local.readText())
        local.writeText("unsynced")
        assertTrue(mirror().prepare().dirty)
        assertEquals("unsynced", local.readText())
    }

    @Test fun normalizedDestinationIsReportedOnceAndNotRetried() = runBlocking {
        provider.normalizeAllFileNames = true
        provider.rejectRename = true
        val mirror = mirror()
        val root = mirror.prepare().worktree
        File(root, "review.md").writeText("review")

        val result = mirror.sync()

        assertEquals(MirrorSync.Failed, result.state)
        assertEquals("review.md.txt", result.path)
        assertEquals(1, provider.createCalls)
        assertEquals(MirrorSync.Conflict, mirror.sync().state)
        assertEquals(1, provider.createCalls)
        assertEquals("review", File(root, "review.md").readText())
    }

    @Test fun localSymlinkIsNeverFollowedIntoSaf() = runBlocking {
        val mirror = mirror()
        val root = mirror.prepare().worktree
        val outside = File(files, "outside-secret").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())

        try { mirror.sync(); fail("Expected unsafe entry") }
        catch (error: MirrorFailure) { assertEquals("unsafe_entry", error.code) }
        assertTrue(workspace.listDirectory(WorkspacePath.ROOT).isEmpty())
    }

    @Test fun deselectedWorkspaceCannotReceiveMirrorChanges() = runBlocking {
        source("file.txt", "base".toByteArray())
        var selected = true
        val mirror = WorkspaceMirror(app, workspace) { selected }
        File(mirror.prepare().worktree, "file.txt").writeText("old workspace edit")
        selected = false

        try { mirror.sync(); fail("Expected workspace change") }
        catch (error: MirrorFailure) { assertEquals("workspace_changed", error.code) }
        assertArrayEquals("base".toByteArray(), bytes("file.txt"))
        assertEquals("old workspace edit", File(mirror.worktree, "file.txt").readText())
    }

    @Test fun cancelledMaterializationLeavesNoCommittedWorktreeAndCanRetry() = runBlocking {
        source("file.txt", "base".toByteArray())
        val mirror = mirror()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        provider.blockNextRead = gate
        provider.readStarted = started
        val running = launch { mirror.prepare() }
        assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        running.cancelAndJoin()
        assertFalse(mirror.worktree.exists())
        assertEquals("base", File(mirror.prepare().worktree, "file.txt").readText())
    }
}
