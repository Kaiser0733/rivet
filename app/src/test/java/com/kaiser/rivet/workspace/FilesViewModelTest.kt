package com.kaiser.rivet.workspace

import android.content.Intent
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.kaiser.rivet.ui.files.FilesState
import com.kaiser.rivet.ui.files.FilesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FilesViewModelTest {
    private lateinit var provider: TestDocumentsProvider
    private var store = ViewModelStore()
    private lateinit var documentId: String
    private val app get() = RuntimeEnvironment.getApplication()
    private val factory get() = ViewModelProvider.AndroidViewModelFactory(app)
    private fun model() = ViewModelProvider(store, factory)[FilesViewModel::class.java]
    private suspend fun await(vm: FilesViewModel, predicate: (FilesState) -> Boolean): FilesState =
        withTimeout(5000) { vm.state.first(predicate) }

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val tree = DocumentsContract.buildTreeDocumentUri("com.kaiser.rivet.testvm", "root")
        val info = ProviderInfo().apply {
            authority = tree.authority; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info).get()
        val selection = WorkspaceSelection(app)
        val workspace = selection.select(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val entry = workspace.createFile(WorkspacePath.parse("a.kt"))
        documentId = entry.documentId
        provider.nodes[documentId]!!.bytes.writeText("old")
        selection.rememberLocation(workspace, WorkspacePath.ROOT, entry.path)
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun activityStoreRetainsDraftButNewStoreReloadsFileBytes() = runBlocking {
        val vm = model()
        await(vm) { it.snapshot != null && !it.loading }
        vm.edit("unsaved")
        assertSame(vm, model())
        assertEquals("unsaved", model().state.value.draft)
        store.clear()
        store = ViewModelStore()
        val restored = await(model()) { it.snapshot != null && !it.loading }
        assertEquals("a.kt", restored.opened?.path?.value)
        assertEquals("old", restored.draft)
        assertFalse(restored.dirty)
    }
    @Test fun dirtyEditorCannotNavigateAndConflictKeepsDraft() = runBlocking {
        val vm = model()
        await(vm) { it.snapshot != null && !it.loading }
        vm.edit("draft")
        vm.navigate(WorkspacePath.ROOT)
        assertNotNull(vm.state.value.opened)
        provider.nodes[documentId]!!.bytes.writeText("external")
        vm.save()
        val failed = await(vm) { !it.mutating && it.error != null }
        assertEquals("draft", failed.draft)
        assertTrue(failed.dirty)
        assertEquals("external", provider.nodes[documentId]!!.bytes.readText())
    }
    @Test fun saveRefreshesSnapshotAndDisplayedMetadata() = runBlocking {
        val vm = model()
        await(vm) { it.snapshot != null && !it.loading }
        vm.edit("longer draft")
        vm.save()
        val saved = await(vm) { !it.mutating && !it.dirty }
        assertNull(saved.error)
        assertEquals(saved.snapshot?.size, saved.opened?.size)
        assertEquals("longer draft", provider.nodes[documentId]!!.bytes.readText())
    }
    @Test fun rejectedWorkspaceSelectionKeepsExistingDraft() = runBlocking {
        val vm = model()
        await(vm) { it.snapshot != null && !it.loading }
        vm.edit("keep me")
        vm.select(Uri.parse("content://missing/tree/nope"), 0)
        val rejected = await(vm) { !it.mutating && it.error != null }
        assertTrue(rejected.selected)
        assertEquals("keep me", rejected.draft)
        assertEquals("a.kt", rejected.opened?.path?.value)
    }

    @Test fun workspaceIdentityWaiterCompletesOnReplacement() = runBlocking {
        val selection = WorkspaceSelection(app)
        val expected = selection.currentIdentity()!!
        val changed = async { selection.awaitIdentityChange(expected) }
        yield()

        app.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit()
            .putString("tree", "content://replacement/tree/root").commit()

        withTimeout(1000) { changed.await() }
    }
}
