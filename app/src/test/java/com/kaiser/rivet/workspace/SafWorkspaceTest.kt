package com.kaiser.rivet.workspace

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
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
class SafWorkspaceTest {
    private lateinit var provider: TestDocumentsProvider
    private lateinit var workspace: SafWorkspace
    private val tree = DocumentsContract.buildTreeDocumentUri("com.kaiser.rivet.testdocs", "root")
    private val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

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
        resolver.takePersistableUriPermission(tree, grantFlags)
        workspace = SafWorkspace(resolver, tree)
    }
    private fun path(value: String) = WorkspacePath.parse(value)
    private suspend fun failure(reason: WorkspaceFailure.Reason, operation: suspend () -> Unit) {
        try { operation(); fail("Expected $reason") } catch (e: WorkspaceFailure) { assertEquals(reason, e.reason) }
    }

    @Test fun nativeCreateListReadWriteRenameMoveDelete() = runBlocking {
        workspace.createDirectory(path("src"))
        workspace.createFile(path("notes.kt"))
        val empty = workspace.readTextFile(path("notes.kt"))
        val saved = workspace.writeTextFile(path("notes.kt"), "val n = 1\n", empty.sha256)
        assertEquals("val n = 1\n", saved.text)
        val renamed = workspace.rename(path("notes.kt"), "main.kt")
        assertEquals("main.kt", renamed.path.value)
        val moved = workspace.move(path("main.kt"), path("src"))
        assertEquals("src/main.kt", moved.path.value)
        assertEquals(saved.sha256, workspace.readTextFile(moved.path).sha256)
        workspace.delete(moved.path)
        assertTrue(workspace.listDirectory(path("src")).isEmpty())
    }
    @Test fun listingQueriesOnlyOneDirectChildCollection() = runBlocking {
        workspace.createFile(path("z"))
        workspace.createFile(path("a"))
        workspace.createDirectory(path("dir"))
        workspace.createFile(path("dir/hidden"))
        provider.childQueries = 0
        assertEquals(listOf("dir", "a", "z"), workspace.listDirectory(WorkspacePath.ROOT).map { it.path.value })
        assertEquals(1, provider.childQueries)
    }
    @Test fun conflictDoesNotOverwriteExternalChanges() = runBlocking {
        val entry = workspace.createFile(path("a"))
        val snapshot = workspace.readTextFile(entry.path)
        provider.nodes[entry.documentId]!!.bytes.writeText("external")
        failure(WorkspaceFailure.Reason.CONFLICT) { workspace.writeTextFile(entry.path, "draft", snapshot.sha256) }
        assertEquals("external", provider.nodes[entry.documentId]!!.bytes.readText())
    }
    @Test fun patchValidationFailureDoesNotMutateDocument() = runBlocking {
        val entry = workspace.createFile(path("a"))
        provider.nodes[entry.documentId]!!.bytes.writeText("abc")
        val snapshot = workspace.readTextFile(entry.path)
        failure(WorkspaceFailure.Reason.PATCH) {
            workspace.applyTextPatch(entry.path, snapshot.sha256, listOf(TextEdit("a", "A"), TextEdit("missing", "X")))
        }
        assertEquals("abc", provider.nodes[entry.documentId]!!.bytes.readText())
        val patched = workspace.applyTextPatch(entry.path, snapshot.sha256, listOf(TextEdit("b", "B")))
        assertEquals("aBc", patched.text)
    }
    @Test fun rootAndDuplicatesAndMissingParentsAreProtected() = runBlocking {
        failure(WorkspaceFailure.Reason.ROOT) { workspace.delete(WorkspacePath.ROOT) }
        failure(WorkspaceFailure.Reason.ROOT) { workspace.move(WorkspacePath.ROOT, path("x")) }
        failure(WorkspaceFailure.Reason.ROOT) { workspace.rename(WorkspacePath.ROOT, "x") }
        workspace.createFile(path("a"))
        failure(WorkspaceFailure.Reason.DUPLICATE) { workspace.createFile(path("a")) }
        failure(WorkspaceFailure.Reason.MISSING) { workspace.createFile(path("missing/a")) }
        failure(WorkspaceFailure.Reason.NOT_DIRECTORY) { workspace.createFile(path("a/child")) }
        assertEquals(2, provider.nodes.size)
    }
    @Test fun readOnlyProviderStillReadsButRejectsMutations() = runBlocking {
        val entry = workspace.createFile(path("a"))
        provider.writable = false
        assertEquals("", workspace.readTextFile(entry.path).text)
        failure(WorkspaceFailure.Reason.UNSUPPORTED) { workspace.createDirectory(path("dir")) }
        failure(WorkspaceFailure.Reason.UNSUPPORTED) { workspace.delete(entry.path) }
        failure(WorkspaceFailure.Reason.UNSUPPORTED) { workspace.rename(entry.path, "b") }
        failure(WorkspaceFailure.Reason.UNSUPPORTED) { workspace.writeTextFile(entry.path, "x", WorkspaceText.sha256(byteArrayOf())) }
    }
    @Test fun rejectedDeleteDoesNotReportSuccess() = runBlocking {
        val entry = workspace.createFile(path("a"))
        provider.rejectDelete = true
        try { workspace.delete(entry.path); fail("Rejected deletion reported success") } catch (_: WorkspaceFailure) { }
        assertTrue(provider.nodes.containsKey(entry.documentId))
    }
    @Test fun nullMetadataAndBinaryAndLargeFilesAreSafe() = runBlocking {
        val entry = workspace.createFile(path("a"))
        provider.reportMetadata = false
        assertNull(workspace.stat(entry.path).size)
        provider.nodes[entry.documentId]!!.bytes.writeBytes(byteArrayOf(0, 1))
        failure(WorkspaceFailure.Reason.BINARY) { workspace.readTextFile(entry.path) }
        provider.nodes[entry.documentId]!!.bytes.writeBytes(ByteArray(WorkspaceText.MAX_BYTES + 1))
        failure(WorkspaceFailure.Reason.TOO_LARGE) { workspace.readTextFile(entry.path) }
    }
    @Test fun persistedSelectionRestoresAndRevocationIsRecoverable() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val selection = WorkspaceSelection(app)
        val selected = selection.select(tree, grantFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        selected.createDirectory(path("src"))
        selection.rememberDirectory(selected, path("src"))
        val restored = WorkspaceSelection(app).restore()!!
        assertEquals(tree, restored.first.tree)
        assertEquals("src", restored.second.value)
        app.contentResolver.releasePersistableUriPermission(tree, grantFlags)
        failure(WorkspaceFailure.Reason.PERMISSION) { restored.first.stat(WorkspacePath.ROOT) }
    }
}
