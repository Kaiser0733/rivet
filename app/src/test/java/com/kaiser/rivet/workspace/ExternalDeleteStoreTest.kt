package com.kaiser.rivet.workspace

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.RoutedAgentWorkspace
import com.kaiser.rivet.agent.SafAgentWorkspace
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Grant deletes are copied before the provider removes them and Undo puts the bytes back. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ExternalDeleteStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var provider: TestDocumentsProvider
    private lateinit var workspace: SafWorkspace
    private val tree = DocumentsContract.buildTreeDocumentUri("com.kaiser.rivet.testdocs", "root")

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
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        workspace = SafWorkspace(resolver, tree)
    }

    private fun path(value: String) = WorkspacePath.parse(value)

    private fun store(maxBytes: Long = 500L * 1024 * 1024): ExternalDeleteStore =
        ExternalDeleteStore(
            temporary.newFolder("trash-${temporary.root.listFiles()?.size ?: 0}"),
            { SafWorkspace(RuntimeEnvironment.getApplication().contentResolver, tree) },
            maxBytes = maxBytes,
        )

    private fun router() = RoutedAgentWorkspace(
        null, { _, _ -> SafAgentWorkspace(workspace) }, null,
    )

    private fun executor(store: ExternalDeleteStore) = AgentToolExecutor(
        router(),
        externalDeletes = store,
        currentSessionId = { "session-1" },
        currentTurnId = "turn-1",
    )

    private suspend fun mkdir(raw: String) {
        val target = path(raw)
        for (index in 1..target.segments.size) {
            val partial = WorkspacePath.parse(target.segments.take(index).joinToString("/"))
            val exists = try { workspace.stat(partial).directory } catch (_: WorkspaceFailure) { false }
            if (!exists) workspace.createDirectory(partial)
        }
    }

    private suspend fun write(raw: String, bytes: ByteArray) {
        val target = path(raw)
        val exists = try { workspace.stat(target) } catch (_: WorkspaceFailure) { null }
        if (exists == null) workspace.createFileWithParents(target)
        val entry = workspace.stat(target)
        workspace.writeFileFrom(target, ByteArrayInputStream(bytes), EMPTY_SHA256, entry.documentId)
    }

    private suspend fun read(raw: String): ByteArray = ByteArrayOutputStream().use { output ->
        workspace.copyFileTo(path(raw), output)
        output.toByteArray()
    }

    private suspend fun missing(raw: String): Boolean = try {
        workspace.stat(path(raw))
        false
    } catch (_: WorkspaceFailure) {
        true
    }

    private suspend fun delete(prepared: com.kaiser.rivet.agent.PreparedAgentTool) =
        prepared.execute()

    @Test
    fun externalDeleteIsStagedAndUndoRestoresTheExactBytes() = runBlocking {
        val bytes = byteArrayOf(0, 1, 2, 3, 0x7f, 0x55, 0x00, 0x10)
        write("notes.bin", bytes)
        val store = store()
        val executor = executor(store)
        val prepared = requireNotNull(executor.prepare(
            AgentToolCall("delete", "delete_path", """{"path":"grants/grant/notes.bin"}"""),
        ))
        val described = executor.describeDestructive(requireNotNull(prepared.approval))

        assertTrue(described.detail, described.detail.contains("Rivet keeps a copy"))
        assertFalse(described.detail, described.detail.contains("Deletion is permanent"))

        val result = delete(prepared)
        assertFalse(result.content, result.error)
        assertTrue(missing("notes.bin"))

        val undo = requireNotNull(store.latest("session-1"))
        assertEquals(1, undo.fileCount)
        val restored = store.undo(undo.id)
        assertEquals(1, restored.fileCount)
        assertArrayEquals(bytes, read("notes.bin"))
        assertNull(store.latest("session-1"))
    }

    @Test
    fun overCapFileIsNamedAndTheDeleteOnlyProceedsWithoutAnUndoPromise() = runBlocking {
        write("big.bin", byteArrayOf(1, 2, 3, 4, 5))
        val store = store(maxBytes = 4)
        val executor = executor(store)
        val prepared = requireNotNull(executor.prepare(
            AgentToolCall("delete", "delete_path", """{"path":"grants/grant/big.bin"}"""),
        ))
        val described = executor.describeDestructive(requireNotNull(prepared.approval))

        assertTrue(described.detail, described.detail.contains("too large to keep a copy"))
        assertTrue(described.detail, described.detail.contains("5 bytes"))
        assertTrue(described.detail, described.detail.contains("cannot be undone"))
        assertFalse(described.detail, described.detail.contains("Rivet keeps a copy"))
        assertFalse(described.undoable)

        val result = delete(prepared)
        assertFalse(result.content, result.error)
        assertTrue(missing("big.bin"))
        assertNull(store.latest("session-1"))
    }

    @Test
    fun deletedFolderComesBackWithItsFilesAndEmptyDirectories() = runBlocking {
        mkdir("folder/empty")
        mkdir("folder/nested")
        write("folder/a.txt", "alpha".toByteArray())
        write("folder/nested/b.bin", byteArrayOf(9, 8, 7))
        val store = store()
        val executor = executor(store)
        val prepared = requireNotNull(executor.prepare(
            AgentToolCall("delete", "delete_path", """{"path":"grants/grant/folder"}"""),
        ))

        assertFalse(delete(prepared).error)
        assertTrue(missing("folder"))

        val undo = requireNotNull(store.latest("session-1"))
        store.undo(undo.id)

        assertArrayEquals("alpha".toByteArray(), read("folder/a.txt"))
        assertArrayEquals(byteArrayOf(9, 8, 7), read("folder/nested/b.bin"))
        assertTrue(workspace.stat(path("folder/empty")).directory)
    }

    @Test
    fun twoDeletesInOneTurnShareOneUndoRowAndBothComeBack() = runBlocking {
        write("one.bin", byteArrayOf(1))
        write("two.bin", byteArrayOf(2, 2))
        val store = store()
        val executor = executor(store)
        for (name in listOf("one.bin", "two.bin")) {
            val prepared = requireNotNull(executor.prepare(
                AgentToolCall("delete-$name", "delete_path", """{"path":"grants/grant/$name"}"""),
            ))
            assertFalse(delete(prepared).error)
        }

        val undo = requireNotNull(store.latest("session-1"))
        assertEquals(2, undo.fileCount)
        assertEquals(2, undo.paths.size)
        store.undo(undo.id)
        assertArrayEquals(byteArrayOf(1), read("one.bin"))
        assertArrayEquals(byteArrayOf(2, 2), read("two.bin"))
    }

    @Test
    fun failedProviderDeleteIsAnErrorAndDiscardsTheStagedCopy() = runBlocking {
        write("keep.bin", byteArrayOf(1, 2, 3))
        val store = store()
        val executor = executor(store)
        val prepared = requireNotNull(executor.prepare(
            AgentToolCall("delete", "delete_path", """{"path":"grants/grant/keep.bin"}"""),
        ))
        provider.rejectDelete = true

        val result = delete(prepared)

        assertTrue(result.content, result.error)
        assertFalse(missing("keep.bin"))
        assertNull(store.latest("session-1"))
    }

    @Test
    fun undoStopsBeforeOverwritingANewerFileAtTheSamePath() = runBlocking {
        write("race.bin", byteArrayOf(1, 2, 3))
        val store = store()
        val executor = executor(store)
        val prepared = requireNotNull(executor.prepare(
            AgentToolCall("delete", "delete_path", """{"path":"grants/grant/race.bin"}"""),
        ))
        assertFalse(delete(prepared).error)
        val undo = requireNotNull(store.latest("session-1"))
        write("race.bin", byteArrayOf(4, 5, 6))

        val failure = runCatching { store.undo(undo.id) }.exceptionOrNull()

        assertTrue(failure is ExternalDeleteFailure)
        assertEquals("undo_conflict", (failure as ExternalDeleteFailure).code)
        assertArrayEquals(byteArrayOf(4, 5, 6), read("race.bin"))
        assertNotNull(store.latest("session-1"))
    }

    @Test
    fun unreadableFileIsNotPromisedAnUndo() = runBlocking {
        write("secret.bin", byteArrayOf(1, 2, 3))
        provider.rejectReadAfterFirstFor = "secret.bin"
        val store = store()
        val executor = executor(store)
        val prepared = requireNotNull(executor.prepare(
            AgentToolCall("delete", "delete_path", """{"path":"grants/grant/secret.bin"}"""),
        ))
        // observeStructural hashes the file once; the plan's readability probe is the
        // second read and is refused, so the approval must not promise an Undo copy.
        val described = executor.describeDestructive(requireNotNull(prepared.approval))
        assertTrue(described.detail, described.detail.contains("could not read it to keep an Undo copy"))
        assertFalse(described.undoable)
    }

    private companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
