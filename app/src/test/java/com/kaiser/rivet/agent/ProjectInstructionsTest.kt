package com.kaiser.rivet.agent

import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.TestDocumentsProvider
import com.kaiser.rivet.workspace.WorkspacePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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
class ProjectInstructionsTest {
    private val tree = DocumentsContract.buildTreeDocumentUri("com.kaiser.rivet.instructions", "root")
    private lateinit var provider: TestDocumentsProvider
    private lateinit var workspace: SafWorkspace

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
        resolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        workspace = SafWorkspace(resolver, tree)
    }

    private suspend fun instruction(path: String, content: String) {
        val entry = workspace.createFile(WorkspacePath.parse(path))
        provider.nodes[entry.documentId]!!.bytes.writeText(content)
    }

    @Test fun rootAndOnlyApplicableNestedInstructionsLoadInOrder() = runBlocking {
        workspace.createDirectory(WorkspacePath.parse("src"))
        workspace.createDirectory(WorkspacePath.parse("other"))
        instruction("AGENTS.md", "root rule")
        instruction("src/AGENTS.md", "src rule")
        instruction("other/AGENTS.md", "other rule")
        val loader = ProjectInstructions(workspace)

        val result = loader.load(mapOf(WorkspacePath.parse("src/Main.kt") to false))

        assertTrue(result.text.indexOf("root rule") < result.text.indexOf("src rule"))
        assertFalse(result.text.contains("other rule"))
        assertTrue(result.files == listOf("AGENTS.md", "src/AGENTS.md"))
        assertFalse(result.limited)
    }

    @Test fun oversizedAndMalformedInstructionsAreMarkedAndBounded() = runBlocking {
        workspace.createDirectory(WorkspacePath.parse("src"))
        instruction("AGENTS.md", "x".repeat(ProjectInstructions.PER_FILE_BYTES + 1))
        instruction("src/AGENTS.md", "\u0000bad")
        val result = ProjectInstructions(workspace).load(mapOf(WorkspacePath.parse("src/Main.kt") to false))
        assertTrue(result.limited)
        assertTrue(result.text.contains("AGENTS.md: skipped"))
        assertTrue(result.text.contains("src/AGENTS.md: unreadable"))
        assertFalse(result.text.contains("x".repeat(100)))
    }

    @Test fun toolTargetsRemainInsideWorkspaceAndAreDeterministic() {
        val loader = ProjectInstructions(workspace)
        val targets = loader.targets(AgentToolCall("1", "move_path",
            """{"path":"src/A.kt","destination":"lib"}"""))
        assertTrue(targets.keys.map { it.value } == listOf("src/A.kt", "lib"))
        assertTrue(loader.targets(AgentToolCall("2", "read_file", """{"path":"../outside"}""")).isEmpty())
    }
}
