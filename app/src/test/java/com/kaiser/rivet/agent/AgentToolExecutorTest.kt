package com.kaiser.rivet.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExecutorTest {
    internal class FakeWorkspace(
        var text: String = "content",
        var readHash: String = "a".repeat(64),
    ) : AgentWorkspace {
        override suspend fun stat(path: String) = AgentWorkspaceEntry(path, directory = false, size = 80_106)
        var writes = 0
        var deletes = 0
        var failure: String? = null
        var listedEntries = listOf(
            AgentWorkspaceEntry("Main.kt", directory = false, size = 12),
        )
        var searchHits = listOf(AgentSearchHit("src/Main.kt", 2, "content"))
        var searchLimited = false
        var searchFilesScanned = 3
        var searchEntriesVisited = 4
        var searchBytesScanned = 5L
        var searchSkipped = 1
        var createdPath: String? = null

        override suspend fun list(path: String) = listedEntries
        override suspend fun read(path: String) = AgentFileSnapshot(
            path,
            text,
            readHash,
            text.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        override suspend fun search(path: String, query: String) = AgentSearchReport(
            searchHits,
            limited = searchLimited,
            filesScanned = searchFilesScanned,
            entriesVisited = searchEntriesVisited,
            bytesScanned = searchBytesScanned,
            skipped = searchSkipped,
        )
        override suspend fun write(path: String, content: String, expectedHash: String): AgentFileSnapshot {
            failure?.let { throw AgentWorkspaceFailure(it) }
            writes++
            return AgentFileSnapshot(path, content, "b".repeat(64), content.length.toLong())
        }
        override suspend fun patch(path: String, expectedHash: String, edits: List<AgentTextEdit>) =
            write(path, edits.single().newText, expectedHash)
        override suspend fun createFile(path: String) = AgentCreatedFile(createdPath ?: path, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 0)
        override suspend fun createDirectory(path: String) = AgentWorkspaceEntry(path, directory = true, size = null)
        override suspend fun rename(path: String, newName: String) = AgentWorkspaceEntry(
            path.substringBeforeLast('/', "").let { parent -> if (parent.isEmpty()) newName else "$parent/$newName" },
            directory = false,
            size = 0,
        )
        override suspend fun move(path: String, destination: String) = AgentWorkspaceEntry(
            if (destination.isEmpty()) path.substringAfterLast('/') else "$destination/${path.substringAfterLast('/')}",
            directory = false,
            size = 0,
        )
        override suspend fun delete(path: String) { deletes++ }
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
    fun listOmitsDirectorySizeButKeepsFileSize() = runTest {
        val workspace = FakeWorkspace().apply {
            listedEntries = listOf(
                AgentWorkspaceEntry("src", directory = true, size = 4096),
                AgentWorkspaceEntry("README.md", directory = false, size = 42),
            )
        }

        val result = AgentToolExecutor(workspace).prepare(AgentToolCall(
            "list", "list_directory", "{}",
        )).execute()
        val entries = Json.parseToJsonElement(result.content).jsonObject["entries"]!!.jsonArray

        assertEquals(null, entries[0].jsonObject["size"])
        assertEquals("42", entries[1].jsonObject["size"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchExposesCompletenessAndScanMetadata() = runTest {
        val workspace = FakeWorkspace()

        val result = AgentToolExecutor(workspace).prepare(AgentToolCall(
            "search", "search_files", """{"query":"content"}""",
        )).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject

        assertEquals("3", content["files_scanned"]?.jsonPrimitive?.content)
        assertEquals("4", content["entries_visited"]?.jsonPrimitive?.content)
        assertEquals("5", content["bytes_scanned"]?.jsonPrimitive?.content)
        assertEquals("1", content["skipped"]?.jsonPrimitive?.content)
        assertFalse(content["limited"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun broadSearchReturnsBoundedCompleteHitsInsteadOfOutputLimit() = runTest {
        val workspace = FakeWorkspace().apply {
            searchHits = (1..200).map { index ->
                AgentSearchHit("src/${"p".repeat(120)}-$index.kt", index, "x".repeat(240))
            }
        }

        val result = AgentToolExecutor(workspace).prepare(AgentToolCall(
            "broad", "search_files", """{"query":"x"}""",
        )).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject
        val hits = content["hits"]!!.jsonArray

        assertFalse(result.error)
        assertTrue(result.content.toByteArray(Charsets.UTF_8).size <= AgentLoop.MAX_TOOL_RESULT_BYTES)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.size < workspace.searchHits.size)
        assertEquals(
            workspace.searchHits.take(hits.size).map { it.path },
            hits.map { it.jsonObject["path"]!!.jsonPrimitive.content },
        )
        assertTrue(content["limited"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun normalizedCreateResultNamesTheRequestedAndActualPaths() = runTest {
        val workspace = FakeWorkspace().apply { createdPath = "review.md.txt" }

        val result = AgentToolExecutor(workspace).prepare(AgentToolCall(
            "create", "create_file", """{"path":"review.md"}""",
        )).execute()
        val content = Json.parseToJsonElement(result.content).jsonObject

        assertFalse(result.error)
        assertEquals("review.md.txt", content["path"]!!.jsonPrimitive.content)
        assertEquals("review.md", content["requested_path"]!!.jsonPrimitive.content)
        assertEquals("Created  review.md.txt", result.summary)
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
            AgentToolCall("dot", "create_file", """{"path":"trailing."}"""),
            AgentToolCall("x", "read_file", """{"path":"A","extra":true}"""),
        )

        val results = calls.map { executor.prepare(it).execute() }

        assertTrue(results.all { it.error })
        assertEquals(listOf("unknown_tool", "invalid_arguments", "invalid_arguments", "invalid_path", "invalid_path", "invalid_arguments"),
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

    @Test
    fun catalogDocumentsSearchAndPatchContracts() {
        val descriptions = AgentToolExecutor.definitions.associate { it.name to it.description }

        assertTrue(descriptions.getValue("search_files").contains("literal"))
        assertTrue(descriptions.getValue("search_files").contains("case-sensitive"))
        listOf("limited", "files_scanned", "entries_visited", "bytes_scanned", "skipped").forEach {
            assertTrue(descriptions.getValue("search_files").contains(it))
        }
        assertTrue(descriptions.getValue("apply_patch").contains("sequentially"))
        assertTrue(descriptions.getValue("apply_patch").contains("later edit"))
        assertTrue(descriptions.getValue("apply_patch").contains("before the final write"))
        assertTrue(descriptions.getValue("apply_patch").contains("hash"))
    }
}
