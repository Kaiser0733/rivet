package com.kaiser.rivet.starter

import com.kaiser.rivet.agent.AgentActivityProjection
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.ProjectContext
import com.kaiser.rivet.agent.StarterBrief
import com.kaiser.rivet.agent.modelMessages
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterInstallTest {
    @Test fun refusesPathEscapesBeforeAnyCopy() {
        for (path in listOf("../escape", "public/../escape", "/escape", "C:/escape", "foo\\escape",
            "foo//bar", "foo/./bar")) {
            assertThrows(StarterInstallFailure::class.java) { StarterInstall.read(archive(path)) }
        }
    }

    @Test fun refusesAnExistingTreeWithTheExactCopyAndNoWrites() {
        val files = StarterInstall.read(archive("nested/note.txt"))
        val written = mutableListOf<String>()
        val error = assertThrows(StarterInstallFailure::class.java) {
            runBlocking { StarterInstall.copy(files, isEmpty = { false }) { written += it.path.value } }
        }
        assertEquals("That folder already has files. Pick an empty folder for a starter.", error.message)
        assertTrue(written.isEmpty())
        runBlocking { StarterInstall.copy(files, isEmpty = { true }) { written += it.path.value } }
        assertEquals(listOf("nested", "README.md", "index.html", "nested/note.txt"), written)
    }

    @Test fun preservesAnExplicitEmptyDirectoryAndItsParent() {
        val entries = StarterInstall.read(archive("nested/empty/", data = byteArrayOf()))
        assertEquals(listOf("nested", "nested/empty"), entries.filter { it.bytes == null }.map { it.path.value })
    }

    @Test fun refusesNonProjectZipAndBinaryContent() {
        assertThrows(StarterInstallFailure::class.java) {
            StarterInstall.read(archive("note.txt", includeRoot = false))
        }
        assertThrows(StarterInstallFailure::class.java) {
            StarterInstall.read(archive("note.txt", data = byteArrayOf(0xFF.toByte())))
        }
    }

    @Test fun briefSurvivesSerializationAndCompactionWithoutAVisibleBubble() {
        val workspace = "content://example/tree/project"
        val brief = "Interview the user before touching the landing page."
        val event = StarterBrief.event(workspace, brief)
        val persisted = Json.decodeFromString(AgentMessage.serializer(),
            Json.encodeToString(AgentMessage.serializer(), event))
        assertEquals(AgentRole.Context, persisted.role)
        assertTrue(persisted.text.isEmpty())
        assertTrue(AgentActivityProjection.conversation(listOf(persisted)).isEmpty())
        assertNotNull(StarterBrief.pending(listOf(persisted), workspace))
        assertNull(StarterBrief.pending(listOf(persisted, AgentMessage.assistant("What problems do you see?")), workspace))

        val model = modelMessages(listOf(persisted), workspace, enforceWorkspace = true)
        assertEquals(AgentRole.User, model.single().role)
        assertTrue(model.single().text.contains(brief))
        assertTrue(modelMessages(listOf(persisted), "other", enforceWorkspace = true).isEmpty())

        val compacted = ProjectContext.projection(listOf(persisted), emptyList(), workspaceOverride = workspace)
        assertEquals(model, modelMessages(compacted, workspace, enforceWorkspace = true))
    }

    private fun archive(path: String, includeRoot: Boolean = true, data: ByteArray = "file".encodeToByteArray()): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                if (includeRoot) for (name in listOf("README.md", "index.html")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("starter".encodeToByteArray())
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(path))
                zip.write(data)
                zip.closeEntry()
            }
        }.toByteArray()
}
