package com.kaiser.rivet.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExecutorTest {
    private class FakeWorkspace : AgentWorkspace {
        var writes = 0
        var failure: String? = null

        override suspend fun list(path: String) = listOf(
            AgentWorkspaceEntry("$path/Main.kt".trimStart('/'), directory = false, size = 12),
        )
        override suspend fun read(path: String) = AgentFileSnapshot(path, "content", "a".repeat(64), 7)
        override suspend fun search(path: String, query: String) = AgentSearchReport(
            listOf(AgentSearchHit("src/Main.kt", 2, query)), limited = false,
        )
        override suspend fun write(path: String, content: String, expectedHash: String): AgentFileSnapshot {
            failure?.let { throw AgentWorkspaceFailure(it) }
            writes++
            return AgentFileSnapshot(path, content, "b".repeat(64), content.length.toLong())
        }
        override suspend fun patch(path: String, expectedHash: String, edits: List<AgentTextEdit>) =
            write(path, edits.single().newText, expectedHash)
        override suspend fun createFile(path: String) = AgentFileSnapshot(path, "", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 0)
        override suspend fun createDirectory(path: String) = Unit
        override suspend fun rename(path: String, newName: String) = Unit
        override suspend fun move(path: String, destination: String) = Unit
        override suspend fun delete(path: String) = Unit
    }

    @Test
    fun readOnlyCallExecutesWithoutApproval() = runTest {
        val executor = AgentToolExecutor(FakeWorkspace())
        val prepared = executor.prepare(AgentToolCall("r", "read_file", """{"path":"src/Main.kt"}"""))

        val result = prepared.execute()

        assertEquals(null, prepared.approval)
        assertFalse(result.error)
        assertTrue("\"sha256\":\"${"a".repeat(64)}\"" in result.content)
        assertTrue("Read  src/Main.kt" == result.summary)
    }

    @Test
    fun mutationIsPreparedButCannotExecuteBeforeCallerApproval() = runTest {
        val workspace = FakeWorkspace()
        val executor = AgentToolExecutor(workspace)
        val prepared = executor.prepare(AgentToolCall(
            "w", "write_file",
            """{"path":"src/Main.kt","content":"new","expected_sha256":"${"a".repeat(64)}"}""",
        ))

        assertNotNull(prepared.approval)
        assertEquals(0, workspace.writes)
        val result = prepared.execute()
        assertFalse(result.error)
        assertEquals(1, workspace.writes)
    }

    @Test
    fun unknownMalformedMissingAndTraversalAreControlledErrors() = runTest {
        val executor = AgentToolExecutor(FakeWorkspace())
        val calls = listOf(
            AgentToolCall("u", "run_command", "{}"),
            AgentToolCall("j", "read_file", "{"),
            AgentToolCall("m", "read_file", "{}"),
            AgentToolCall("p", "read_file", """{"path":"../secret"}"""),
            AgentToolCall("x", "read_file", """{"path":"A","extra":true}"""),
        )

        val results = calls.map { executor.prepare(it).execute() }

        assertTrue(results.all { it.error })
        assertEquals(listOf("unknown_tool", "invalid_arguments", "invalid_arguments", "invalid_path", "invalid_arguments"),
            results.map { kotlinx.serialization.json.Json.parseToJsonElement(it.content).jsonObject["error"]!!.jsonPrimitive.content })
    }

    @Test
    fun workspaceConflictIsReturnedToModel() = runTest {
        val workspace = FakeWorkspace().apply { failure = "conflict" }
        val executor = AgentToolExecutor(workspace)
        val prepared = executor.prepare(AgentToolCall(
            "w", "write_file",
            """{"path":"A.kt","content":"new","expected_sha256":"${"a".repeat(64)}"}""",
        ))

        val result = prepared.execute()

        assertTrue(result.error)
        assertTrue("\"error\":\"conflict\"" in result.content)
        assertEquals("w", result.callId)
    }

    @Test
    fun catalogContainsOnlyWorkspaceToolsAndClassifiesMutations() {
        val names = AgentToolExecutor.definitions.map { it.name }
        assertEquals(listOf("list_directory", "read_file", "search_files", "write_file", "apply_patch",
            "create_file", "create_directory", "rename_path", "move_path", "delete_path"), names)
        assertTrue(names.none { it in setOf("shell", "terminal", "run_command", "exec", "bash") })
    }
}
