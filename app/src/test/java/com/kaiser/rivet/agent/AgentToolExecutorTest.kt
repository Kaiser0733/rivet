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
    private class FakeWorkspace(
        var text: String = "content",
        var readHash: String = "a".repeat(64),
    ) : AgentWorkspace {
        var writes = 0
        var failure: String? = null

        override suspend fun list(path: String) = listOf(
            AgentWorkspaceEntry("$path/Main.kt".trimStart('/'), directory = false, size = 12),
        )
        override suspend fun read(path: String) = AgentFileSnapshot(
            path,
            text,
            readHash,
            text.toByteArray(Charsets.UTF_8).size.toLong(),
        )
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
        val prepared = executor.prepare(AgentToolCall(
            "r",
            "read_file",
            """{"path":"src/Main.kt","offset":0}""",
        ))

        val result = prepared.execute()
        val content = kotlinx.serialization.json.Json.parseToJsonElement(result.content).jsonObject

        assertEquals(null, prepared.approval)
        assertFalse(result.error)
        assertEquals("content", content["text"]!!.jsonPrimitive.content)
        assertEquals("0", content["offset"]!!.jsonPrimitive.content)
        assertEquals("7", content["bytes"]!!.jsonPrimitive.content)
        assertTrue(content["eof"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(null, content["next_offset"])
        assertEquals("a".repeat(64), content["sha256"]!!.jsonPrimitive.content)
        assertTrue("Read  src/Main.kt" == result.summary)
    }

    @Test
    fun largeFileReturnsBoundedFirstChunkWithContinuation() = runTest {
        val source = "x".repeat(100_000)
        val executor = AgentToolExecutor(FakeWorkspace(text = source))

        val result = executor.prepare(AgentToolCall(
            "large",
            "read_file",
            """{"path":"large.txt"}""",
        )).execute()
        val content = kotlinx.serialization.json.Json.parseToJsonElement(result.content).jsonObject
        val chunk = content["text"]!!.jsonPrimitive.content

        assertFalse(result.error)
        assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertTrue(chunk.length < source.length)
        assertFalse(content["eof"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(content["next_offset"]!!.jsonPrimitive.content.toInt() > 0)
    }

    @Test
    fun multibyteContinuationReassemblesFileAndKeepsFullHash() = runTest {
        val source = "🙂".repeat(3_000) + "tail"
        val expectedHash = "f".repeat(64)
        val executor = AgentToolExecutor(FakeWorkspace(source, expectedHash))

        val firstResult = executor.prepare(AgentToolCall(
            "first",
            "read_file",
            """{"path":"unicode.txt","offset":0}""",
        )).execute()
        val first = kotlinx.serialization.json.Json.parseToJsonElement(firstResult.content).jsonObject
        val next = first["next_offset"]!!.jsonPrimitive.content.toInt()
        val secondResult = executor.prepare(AgentToolCall(
            "second",
            "read_file",
            """{"path":"unicode.txt","offset":$next}""",
        )).execute()
        val second = kotlinx.serialization.json.Json.parseToJsonElement(secondResult.content).jsonObject

        assertTrue(first["text"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertEquals(source, first["text"]!!.jsonPrimitive.content + second["text"]!!.jsonPrimitive.content)
        assertEquals(expectedHash, first["sha256"]!!.jsonPrimitive.content)
        assertEquals(expectedHash, second["sha256"]!!.jsonPrimitive.content)
        assertFalse(first["eof"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(second["eof"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(null, second["next_offset"])
    }

    @Test
    fun readChunkResultAndInvalidMultibyteOffsetAreControlled() = runTest {
        val source = "\\\"".repeat(20_000) + "🙂"
        val executor = AgentToolExecutor(FakeWorkspace(source))

        val bounded = executor.prepare(AgentToolCall(
            "bounded",
            "read_file",
            """{"path":"minified.json","offset":0}""",
        )).execute()
        val invalid = executor.prepare(AgentToolCall(
            "invalid",
            "read_file",
            """{"path":"minified.json","offset":${source.toByteArray(Charsets.UTF_8).size - 1}}""",
        )).execute()

        assertTrue(bounded.content.toByteArray(Charsets.UTF_8).size <= 24 * 1024)
        assertEquals("invalid", invalid.callId)
        assertTrue(invalid.error)
        assertTrue("invalid_offset" in invalid.content)
    }

    @Test
    fun readResultBoundUsesActualEncodedContentSize() = runTest {
        val source = "\u000b".repeat(20_000)
        val executor = AgentToolExecutor(FakeWorkspace(source))

        val result = executor.prepare(AgentToolCall(
            "escaped",
            "read_file",
            """{"path":"escaped.txt"}""",
        )).execute()
        val content = kotlinx.serialization.json.Json.parseToJsonElement(result.content).jsonObject

        assertTrue(result.content.toByteArray(Charsets.UTF_8).size <= AgentLoop.MAX_TOOL_RESULT_BYTES)
        assertTrue(content["bytes"]!!.jsonPrimitive.content.toInt() < 8 * 1024)
        assertFalse(content["eof"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(content["next_offset"]!!.jsonPrimitive.content.toInt() > 0)
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
