package com.kaiser.rivet.agent

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.kaiser.rivet.runtime.ProjectDownloader
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TestDocumentsProvider
import com.kaiser.rivet.workspace.WorkspacePath
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class DownloadActivityTest {
    private lateinit var documents: TestDocumentsProvider
    private lateinit var workspace: SafWorkspace
    private lateinit var staging: File
    private lateinit var executor: AgentToolExecutor

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        staging = Files.createTempDirectory(app.cacheDir.toPath(), "download-activity").toFile()
        val tree = DocumentsContract.buildTreeDocumentUri("com.kaiser.rivet.download-activity", "root")
        val info = ProviderInfo().apply {
            authority = tree.authority
            exported = true
            grantUriPermissions = true
        }
        documents = Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info).get()
        app.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        workspace = SafWorkspace(app.contentResolver, tree)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("downloaded bytes".toResponseBody()).build()
        }.build()
        executor = AgentToolExecutor(SafAgentWorkspace(workspace), downloader = ProjectDownloader(staging, client))
    }

    @After fun cleanup() { staging.deleteRecursively() }

    private fun call(extra: String = "", url: String = "https://example.test/file") =
        AgentToolCall("download", "download_file", """{"url":"$url","path":"target.txt"$extra}""")

    private fun assertProjection(call: AgentToolCall, result: AgentToolResult, outcome: ActivityOutcome,
                                 summary: String) {
        val group = (AgentActivityProjection.conversation(listOf(AgentMessage.assistant("", listOf(call)),
            AgentMessage.tools(listOf(result)))).single() as AgentConversationItem.Activity).group
        assertEquals(outcome, group.operations.single().outcome)
        assertEquals(summary, group.summary)
    }

    @Test fun refusedOverwriteHasCanonicalFailureAndNeverSaysDownloaded() = runBlocking {
        val entry = workspace.createFile(WorkspacePath.parse("target.txt"))
        documents.nodes[entry.documentId]!!.bytes.writeText("existing user bytes")
        val call = call()
        val result = executor.prepare(call).execute()
        assertTrue(result.error)
        assertTrue(result.content.contains("destination_exists"))
        assertEquals("existing user bytes", workspace.readTextFile(entry.path).text)
        assertEquals(1, documents.createCalls)
        assertProjection(call, result, ActivityOutcome.Failed, "Download 1 file (Failed)")
    }

    @Test fun newDownloadHasVerifiedReceiptAndCompletedSummary() = runBlocking {
        val call = call()
        val result = executor.prepare(call).execute()
        assertFalse(result.error)
        assertEquals("downloaded bytes", workspace.readTextFile(WorkspacePath.parse("target.txt")).text)
        assertProjection(call, result, ActivityOutcome.Completed, "Downloaded 1 file")
    }

    @Test fun verifiedOverwriteHasCompletedSummary() = runBlocking {
        val entry = workspace.createFile(WorkspacePath.parse("target.txt"))
        documents.nodes[entry.documentId]!!.bytes.writeText("existing user bytes")
        val hash = workspace.fingerprint(entry.path).sha256
        val call = call(",\"expected_sha256\":\"$hash\"")
        val result = executor.prepare(call).execute()
        assertFalse(result.error)
        assertEquals("downloaded bytes", workspace.readTextFile(entry.path).text)
        assertEquals(1, documents.createCalls)
        assertProjection(call, result, ActivityOutcome.Completed, "Downloaded 1 file")
    }

    @Test fun checksumMismatchCreatesNoFileAndHasFailedSummary() = runBlocking {
        val call = call(",\"content_sha256\":\"${"0".repeat(64)}\"")
        val result = executor.prepare(call).execute()
        assertTrue(result.error)
        assertTrue(result.content.contains("download_sha_mismatch"))
        assertTrue(workspace.listDirectory(WorkspacePath.ROOT).isEmpty())
        assertProjection(call, result, ActivityOutcome.Failed, "Download 1 file (Failed)")
    }

    @Test fun invalidHttpUrlHasFailedSummary() = runBlocking {
        val call = call(url = "http://example.test/file")
        val result = executor.prepare(call).execute()
        assertTrue(result.error)
        assertTrue(workspace.listDirectory(WorkspacePath.ROOT).isEmpty())
        assertProjection(call, result, ActivityOutcome.Failed, "Download 1 file (Failed)")
    }

    @Test fun deniedDownloadNeverCreatesFileOrSaysDownloaded() = runBlocking {
        val call = call()
        val result = AgentToolResult(call.id, call.name, AgentToolError.content("denied"), error = true)
        assertTrue(workspace.listDirectory(WorkspacePath.ROOT).isEmpty())
        assertProjection(call, result, ActivityOutcome.Denied, "Download 1 file (Denied)")
    }
}
