package com.kaiser.rivet.workspace

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AgentLoop
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.SafAgentWorkspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private suspend fun afterApproval(call: AgentToolCall, change: suspend () -> Unit): AgentToolResult {
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))
        val responses = ArrayDeque(listOf(AgentResponse(toolCalls = listOf(call)), AgentResponse(text = "Stopped")))
        val run = AgentLoop(
            requestModel = { _, _, _ -> responses.removeFirst() },
            prepareTool = executor::prepare,
            requestApproval = { change(); true },
            describeDestructive = executor::describeDestructive,
        ).run(listOf(AgentMessage.user("Change the file")), AgentToolExecutor.definitions)
        return run.messages.flatMap { it.toolResults }.single()
    }

    @Test fun selectedProjectUsesProviderDisplayName() = runBlocking {
        assertEquals("project", workspace.displayName())
    }

    @Test fun providerContractSupportsFrameworkQueryAndCreation() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, android.os.CancellationSignal())!!.use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
        }
        val created = DocumentsContract.createDocument(resolver, root, "text/plain", "probe")!!
        assertTrue(DocumentsContract.deleteDocument(resolver, created))
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
    @Test fun uncorrectableCreateReturnsTheActualPathWithoutRetrying() = runBlocking {
        provider.normalizeAllFileNames = true
        provider.normalizeRenamedNames = true
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))

        val result = executor.prepare(AgentToolCall(
            "create", "create_file", """{"path":"review.md"}""",
        )).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject

        assertFalse(result.error)
        assertEquals("review.md.txt", content["path"]!!.jsonPrimitive.content)
        assertEquals("review.md", content["requested_path"]!!.jsonPrimitive.content)
        assertEquals(1, provider.createCalls)
        assertEquals(listOf("review.md.txt"), workspace.listDirectory(WorkspacePath.ROOT).map { it.path.value })
    }
    @Test fun codingNamesAvoidTextSuffixAndKeepHashHandoff() = runBlocking {
        provider.normalizeTextFileNames = true
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))
        val names = listOf("review.md", "README.md", "Main.kt", "index.ts", "package.json", ".gitignore", "Makefile")
        for (name in names) {
            val created = executor.prepare(AgentToolCall("create-$name", "create_file", """{"path":"$name"}""")).execute()
            val value = Json.parseToJsonElement(created.content).jsonObject
            assertFalse(created.error)
            assertEquals(name, value["path"]!!.jsonPrimitive.content)
            assertEquals("0", value["size"]!!.jsonPrimitive.content)
            val hash = value["sha256"]!!.jsonPrimitive.content
            assertEquals(WorkspaceText.sha256(byteArrayOf()), hash)
            val written = executor.prepare(AgentToolCall("write-$name", "write_file",
                """{"path":"$name","content":"hello","expected_sha256":"$hash"}""")).execute()
            assertFalse(written.error)
            assertEquals("hello", workspace.readTextFile(path(name)).text)
        }
        assertEquals(names.size, provider.createCalls)
        assertEquals(0, provider.renameCalls)
        assertTrue(provider.createdMimeTypes.all { it == "application/octet-stream" })
    }
    @Test fun videoMimeTypeScriptStillCreatesWritesReadsAndSearches() = runBlocking {
        provider.videoMimeForTs = true
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))
        val created = executor.prepare(AgentToolCall("create", "create_file", """{"path":"index.ts"}""")).execute()
        assertFalse(created.content, created.error)
        val content = Json.parseToJsonElement(created.content).jsonObject
        assertEquals("index.ts", content["path"]!!.jsonPrimitive.content)
        assertEquals("0", content["size"]!!.jsonPrimitive.content)
        val hash = content["sha256"]!!.jsonPrimitive.content
        assertEquals(WorkspaceText.sha256(byteArrayOf()), hash)
        assertEquals(1, provider.createCalls)
        assertEquals(1, workspace.listDirectory(WorkspacePath.ROOT).size)

        val written = executor.prepare(AgentToolCall("write", "write_file",
            """{"path":"index.ts","content":"const review = 1;","expected_sha256":"$hash"}""")).execute()
        assertFalse(written.content, written.error)
        val read = executor.prepare(AgentToolCall("read", "read_file", """{"path":"index.ts"}""")).execute()
        assertFalse(read.content, read.error)
        assertTrue(read.content.contains("const review = 1;"))
        val report = workspace.search("review", path("index.ts"))
        assertEquals(1, report.filesScanned)
        assertEquals(1, report.entriesVisited)
        assertEquals(1, report.hits.size)
        assertFalse(report.limited)
    }
    @Test fun committedCreateReportsActualPathWhenProviderRefusesInspection() = runBlocking {
        provider.rejectRead = true
        val result = AgentToolExecutor(SafAgentWorkspace(workspace)).prepare(
            AgentToolCall("create", "create_file", """{"path":"index.ts"}"""),
        ).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject
        assertFalse(result.content, result.error)
        assertEquals("index.ts", content["path"]!!.jsonPrimitive.content)
        assertEquals("missing", content["inspection_error"]!!.jsonPrimitive.content)
        assertNull(content["sha256"])
        assertEquals(1, provider.createCalls)
        assertEquals(1, workspace.listDirectory(WorkspacePath.ROOT).size)
    }
    @Test fun fileTargetSearchSkipsBinaryAndOversizedFilesWithoutDirectoryTraversal() = runBlocking {
        val binary = workspace.createFile(path("clip.mp4"))
        provider.nodes[binary.documentId]!!.bytes.writeBytes(byteArrayOf(0, 1, 2))
        provider.binaryMimeByExtension = true
        provider.videoMimeForTs = true
        val ts = workspace.createFile(path("large.ts"))
        provider.nodes[ts.documentId]!!.bytes.writeBytes(ByteArray(300_000) { 'x'.code.toByte() })
        provider.childQueries = 0
        val binaryReport = workspace.search("x", binary.path)
        val largeReport = workspace.search("x", ts.path)
        assertEquals(2, provider.childQueries)
        assertEquals(1, binaryReport.skipped)
        assertEquals(1, largeReport.skipped)
        assertEquals(1, binaryReport.filesScanned)
        assertTrue(largeReport.limited)
        assertEquals(1, largeReport.entriesVisited)
    }
    @Test fun misleadingMimeDoesNotPermitMalformedUtf8ButRealBinaryMimeStillBlocks() = runBlocking {
        provider.videoMimeForTs = true
        val source = workspace.createFile(path("bad.ts"))
        provider.nodes[source.documentId]!!.bytes.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        failure(WorkspaceFailure.Reason.BINARY) { workspace.readTextFile(source.path) }
        val image = workspace.createFile(path("image.png"))
        provider.binaryMimeByExtension = true
        provider.nodes[image.documentId]!!.bytes.writeText("looks textual")
        failure(WorkspaceFailure.Reason.BINARY) { workspace.readTextFile(image.path) }
    }
    @Test fun unstableNameIsRejectedBeforeProviderMutation() = runBlocking {
        failure(WorkspaceFailure.Reason.INVALID_PATH) { workspace.createFile(path("trailing.")) }
        failure(WorkspaceFailure.Reason.INVALID_PATH) { workspace.createDirectory(path("   ")) }
        assertEquals(0, provider.createCalls)
        assertEquals(1, provider.nodes.size)
    }
    @Test fun normalizedCodingNamesAreCorrectedOnceWithoutDuplicates() = runBlocking {
        provider.normalizeAllFileNames = true
        val names = listOf("review.md", "Main.kt", "index.ts", "package.json", ".gitignore", "Makefile")
        for (name in names) assertEquals(name, workspace.createFile(path(name)).path.value)
        assertEquals(names.size, provider.createCalls)
        assertEquals(names.size, provider.renameCalls)
        assertEquals(names.toSet(), workspace.listDirectory(WorkspacePath.ROOT).map { it.path.value }.toSet())
    }
    @Test fun refusedCorrectionStillReturnsCreatedFileAndHash() = runBlocking {
        provider.normalizeAllFileNames = true
        provider.rejectRename = true
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))
        val result = executor.prepare(AgentToolCall("create", "create_file", """{"path":"review.md"}""")).execute()
        val value = Json.parseToJsonElement(result.content).jsonObject
        assertFalse(result.error)
        assertEquals("review.md.txt", value["path"]!!.jsonPrimitive.content)
        assertEquals("review.md", value["requested_path"]!!.jsonPrimitive.content)
        val hash = value["sha256"]!!.jsonPrimitive.content
        assertEquals(WorkspaceText.sha256(byteArrayOf()), hash)
        assertFalse(executor.prepare(AgentToolCall("write", "write_file",
            """{"path":"review.md.txt","content":"edited","expected_sha256":"$hash"}""")).execute().error)
        assertEquals(1, provider.createCalls)
        assertEquals(1, provider.renameCalls)
        assertEquals(1, workspace.listDirectory(WorkspacePath.ROOT).size)
    }
    @Test fun providerWithoutRenameKeepsConfirmedName() = runBlocking {
        provider.normalizeAllFileNames = true
        provider.renameSupported = false
        assertEquals("review.md.txt", workspace.createFile(path("review.md")).path.value)
        assertEquals(1, provider.createCalls)
        assertEquals(0, provider.renameCalls)
    }
    @Test fun normalizedDirectoryCreateReturnsTheActualPath() = runBlocking {
        provider.normalizeDirectoryNames = true
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))

        val result = executor.prepare(AgentToolCall(
            "mkdir", "create_directory", """{"path":"docs"}""",
        )).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject

        assertFalse(result.error)
        assertEquals("docs.folder", content["path"]!!.jsonPrimitive.content)
        assertEquals("docs", content["requested_path"]!!.jsonPrimitive.content)
    }
    @Test fun normalizedRenameReturnsTheActualPath() = runBlocking {
        workspace.createFile(path("draft"))
        provider.normalizeRenamedNames = true
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))

        val result = executor.prepare(AgentToolCall(
            "rename", "rename_path", """{"path":"draft","new_name":"final"}""",
        )).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject

        assertFalse(result.error)
        assertEquals("final.txt", content["path"]!!.jsonPrimitive.content)
        assertEquals("draft", content["source_path"]!!.jsonPrimitive.content)
        assertEquals("final", content["requested_name"]!!.jsonPrimitive.content)
    }
    @Test fun moveReturnsTheConfirmedDestinationPath() = runBlocking {
        workspace.createDirectory(path("src"))
        workspace.createFile(path("draft"))
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))

        val result = executor.prepare(AgentToolCall(
            "move", "move_path", """{"path":"draft","destination":"src"}""",
        )).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject

        assertFalse(result.error)
        assertEquals("src/draft", content["path"]!!.jsonPrimitive.content)
        assertEquals("draft", content["source_path"]!!.jsonPrimitive.content)
        assertEquals("src", content["destination"]!!.jsonPrimitive.content)
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
    @Test fun patchConflictDoesNotOverwriteExternalChanges() = runBlocking {
        val entry = workspace.createFile(path("a"))
        provider.nodes[entry.documentId]!!.bytes.writeText("original")
        val snapshot = workspace.readTextFile(entry.path)
        provider.nodes[entry.documentId]!!.bytes.writeText("external")

        failure(WorkspaceFailure.Reason.CONFLICT) {
            workspace.applyTextPatch(entry.path, snapshot.sha256, listOf(TextEdit("original", "patched")))
        }
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
        failure(WorkspaceFailure.Reason.MISSING) { workspace.move(path("missing"), WorkspacePath.ROOT) }
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
    @Test fun approvedDeleteCannotRemoveAReplacementDocument() = runBlocking {
        val original = workspace.createFile(path("Max.txt"))
        provider.nodes[original.documentId]!!.bytes.writeText("old")
        var replacementId = ""
        val result = afterApproval(AgentToolCall("delete", "delete_path", """{"path":"Max.txt"}""")) {
            provider.nodes.remove(original.documentId)
            val replacement = workspace.createFile(path("Max.txt"))
            replacementId = replacement.documentId
            provider.nodes[replacementId]!!.bytes.writeText("new user data")
        }

        assertTrue(result.error)
        assertTrue(result.content.contains("stale_target"))
        assertEquals(replacementId, workspace.stat(path("Max.txt")).documentId)
        assertEquals("new user data", workspace.readTextFile(path("Max.txt")).text)
    }
    @Test fun approvedRenameCannotRenameAReplacementDocument() = runBlocking {
        val original = workspace.createFile(path("Max.txt"))
        var replacementId = ""
        val result = afterApproval(AgentToolCall("rename", "rename_path",
            """{"path":"Max.txt","new_name":"renamed.txt"}""")) {
            provider.nodes.remove(original.documentId)
            replacementId = workspace.createFile(path("Max.txt")).documentId
        }

        assertTrue(result.error)
        assertTrue(result.content.contains("stale_target"))
        assertEquals(replacementId, workspace.stat(path("Max.txt")).documentId)
        assertFalse(workspace.listDirectory(WorkspacePath.ROOT).any { it.path.value == "renamed.txt" })
    }
    @Test fun approvedMoveCannotMoveAReplacementDocument() = runBlocking {
        workspace.createDirectory(path("target"))
        val original = workspace.createFile(path("Max.txt"))
        var replacementId = ""
        val result = afterApproval(AgentToolCall("move", "move_path",
            """{"path":"Max.txt","destination":"target"}""")) {
            provider.nodes.remove(original.documentId)
            replacementId = workspace.createFile(path("Max.txt")).documentId
        }

        assertTrue(result.error)
        assertTrue(result.content.contains("stale_target"))
        assertEquals(replacementId, workspace.stat(path("Max.txt")).documentId)
        assertTrue(workspace.listDirectory(path("target")).isEmpty())
    }
    @Test fun approvedMoveCannotUseAReplacementDestinationFolder() = runBlocking {
        val originalTarget = workspace.createDirectory(path("target"))
        val source = workspace.createFile(path("Max.txt"))
        var replacementId = ""
        val result = afterApproval(AgentToolCall("move", "move_path",
            """{"path":"Max.txt","destination":"target"}""")) {
            provider.nodes.remove(originalTarget.documentId)
            replacementId = workspace.createDirectory(path("target")).documentId
        }

        assertTrue(result.error)
        assertTrue(result.content.contains("stale_target"))
        assertEquals(source.documentId, workspace.stat(path("Max.txt")).documentId)
        assertEquals(replacementId, workspace.stat(path("target")).documentId)
        assertTrue(workspace.listDirectory(path("target")).isEmpty())
    }
    @Test fun approvedDeleteCannotRemoveFileChangedDuringApproval() = runBlocking {
        val original = workspace.createFile(path("Max.txt"))
        provider.nodes[original.documentId]!!.bytes.writeText("old")
        val result = afterApproval(AgentToolCall("delete", "delete_path", """{"path":"Max.txt"}""")) {
            provider.nodes[original.documentId]!!.bytes.writeText("new user data")
        }

        assertTrue(result.error)
        assertTrue(result.content.contains("stale_target"))
        assertEquals("new user data", workspace.readTextFile(path("Max.txt")).text)
    }
    @Test fun approvedDeleteCannotRemoveDirectoryWithNewExternalContents() = runBlocking {
        workspace.createDirectory(path("folder"))
        val result = afterApproval(AgentToolCall("delete", "delete_path", """{"path":"folder"}""")) {
            val added = workspace.createFile(path("folder/user.txt"))
            provider.nodes[added.documentId]!!.bytes.writeText("new user data")
        }

        assertTrue(result.error)
        assertTrue(result.content.contains("stale_target"))
        assertEquals("new user data", workspace.readTextFile(path("folder/user.txt")).text)
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
    @Test fun directoryAndBinaryReadErrorsRemainDistinct() = runBlocking {
        workspace.createDirectory(path("src"))
        val binary = workspace.createFile(path("image.bin"))
        provider.nodes[binary.documentId]!!.bytes.writeBytes(byteArrayOf(0, 1))
        val executor = AgentToolExecutor(SafAgentWorkspace(workspace))

        suspend fun readError(target: String): String {
            val result = executor.prepare(AgentToolCall(
                target, "read_file", """{"path":"$target"}""",
            )).execute()
            assertTrue(result.error)
            return Json.parseToJsonElement(result.content).jsonObject["error"]!!.jsonPrimitive.content
        }

        assertEquals("not_file", readError("src"))
        assertEquals("binary", readError("image.bin"))
    }
    @Test fun searchDoesNotResolveEveryDirectoryFromRoot() = runBlocking {
        workspace.createDirectory(path("src"))
        workspace.createDirectory(path("src/nested"))
        workspace.createFile(path("src/nested/readme"))
        provider.childQueries = 0
        val report = workspace.search("readme")
        assertEquals(1, report.hits.size)
        assertEquals(3, provider.childQueries)
    }
    @Test fun persistedSelectionRestoresAndRevocationIsRecoverable() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val selection = WorkspaceSelection(app)
        val selected = selection.select(tree, grantFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        selected.createDirectory(path("src"))
        selected.createFile(path("src/a"))
        selection.rememberLocation(selected, path("src"), path("src/a"))
        val restored = WorkspaceSelection(app).restore()!!
        assertEquals(tree, restored.first.tree)
        assertEquals("src", restored.second.value)
        assertEquals("src/a", restored.third?.value)
        app.contentResolver.releasePersistableUriPermission(tree, grantFlags)
        failure(WorkspaceFailure.Reason.PERMISSION) { restored.first.stat(WorkspacePath.ROOT) }
    }
}
