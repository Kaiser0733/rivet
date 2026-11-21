package com.kaiser.rivet.agent

import com.kaiser.rivet.ui.chat.presentedFilesForTurns
import com.kaiser.rivet.workspace.WorkspaceFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PresentedFilesTest {
    private val first = PresentedFile("docs/Plan.md", "The finished plan")
    private val second = PresentedFile("img/Flow.png", "The navigation flow")

    @Test fun verifiesEveryFileAndUsesTheWorkspaceSpelling() = runBlocking {
        val lookups = mutableListOf<String>()
        val confirmed = verifyPresentedFiles(listOf(first, second)) { path ->
            lookups += path
            AgentWorkspaceEntry(path.replace("Plan.md", "Plan (1).md"), directory = false, size = 7)
        }
        assertEquals(listOf(first.path, second.path), lookups)
        assertEquals(listOf(first.copy(path = "docs/Plan (1).md"), second), confirmed)
    }

    @Test fun missingOrDirectoryFailsTheCallWithTheOriginalPath() = runBlocking {
        for (code in listOf("missing", "not_file")) {
            val error = runCatching {
                verifyPresentedFiles(listOf(first, second)) { path ->
                    if (path == second.path && code == "missing") throw AgentWorkspaceFailure("missing")
                    AgentWorkspaceEntry(path, directory = path == second.path, size = null)
                }
            }.exceptionOrNull() as PresentationFailure
            assertEquals(code, error.code)
            assertEquals(second.path, error.path)
        }
    }

    @Test fun validatesCountsAndPathsBeforeReadingAnything() = runBlocking {
        val invalid = listOf(
            emptyList(),
            List(5) { first.copy(path = "file$it") },
            listOf(first, first),
            listOf(first.copy(path = "grants/Downloads/other.md")),
            listOf(first.copy(path = "../other.md")),
            listOf(first.copy(description = "First\nSecond")),
        )
        invalid.forEach { entries ->
            val error = runCatching { verifyPresentedFiles(entries) { error("unexpected stat") } }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException || error is WorkspaceFailure)
        }
    }

    @Test fun toolMetadataSurvivesSerializationAndStaysWithItsOwnTurn() {
        val result = AgentToolResult("call", "present", """{"presented":1}""", presentedFiles = listOf(first))
        val restored = Json.decodeFromString<AgentToolResult>(Json.encodeToString(AgentToolResult.serializer(), result))
        val messages = listOf(
            AgentMessage.user("Write the plan"),
            AgentMessage.tools(listOf(restored)),
            AgentMessage.assistant("Done."),
            AgentMessage.user("Draw the flow"),
            AgentMessage.tools(listOf(restored.copy(error = true, presentedFiles = listOf(second)))),
        )
        assertEquals(mapOf(0 to listOf(first)), presentedFilesForTurns(messages))
    }
}
