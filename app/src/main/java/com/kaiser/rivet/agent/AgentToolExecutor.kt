package com.kaiser.rivet.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentWorkspaceEntry(val path: String, val directory: Boolean, val size: Long?)
data class AgentFileSnapshot(val path: String, val text: String, val sha256: String, val size: Long)
data class AgentSearchHit(val path: String, val line: Int?, val context: String)
data class AgentSearchReport(val hits: List<AgentSearchHit>, val limited: Boolean)
data class AgentTextEdit(val oldText: String, val newText: String)

class AgentWorkspaceFailure(val code: String) : Exception(code)

interface AgentWorkspace {
    suspend fun list(path: String): List<AgentWorkspaceEntry>
    suspend fun read(path: String): AgentFileSnapshot
    suspend fun search(path: String, query: String): AgentSearchReport
    suspend fun write(path: String, content: String, expectedHash: String): AgentFileSnapshot
    suspend fun patch(path: String, expectedHash: String, edits: List<AgentTextEdit>): AgentFileSnapshot
    suspend fun createFile(path: String): AgentFileSnapshot
    suspend fun createDirectory(path: String)
    suspend fun rename(path: String, newName: String)
    suspend fun move(path: String, destination: String)
    suspend fun delete(path: String)
}

class AgentToolExecutor(private val workspace: AgentWorkspace) {
    fun prepare(call: AgentToolCall): PreparedAgentTool {
        if (call.arguments.toByteArray().size > MAX_ARGUMENT_BYTES) return invalid(call, "arguments_too_large")
        return try {
            when (call.name) {
                "list_directory" -> {
                    val args = json.decodeFromString<ListArgs>(call.arguments)
                    val path = path(args.path, root = true)
                    readOnly(call) {
                        val all = workspace.list(path)
                        val entries = all.take(MAX_LIST_ENTRIES)
                        success(call, buildJsonObject {
                            put("path", path)
                            put("entries", buildJsonArray {
                                entries.forEach { entry -> add(buildJsonObject {
                                    put("name", entry.path.substringAfterLast('/'))
                                    put("path", entry.path)
                                    put("type", if (entry.directory) "directory" else "file")
                                    entry.size?.let { put("size", it) }
                                }) }
                            })
                            put("limited", all.size > entries.size)
                        }, "Listed  ${path.ifEmpty { "." }}")
                    }
                }
                "read_file" -> {
                    val args = json.decodeFromString<PathArgs>(call.arguments)
                    val path = path(args.path)
                    readOnly(call) {
                        val file = workspace.read(path)
                        success(call, buildJsonObject {
                            put("path", file.path); put("text", file.text)
                            put("sha256", file.sha256); put("size", file.size)
                        }, "Read  $path")
                    }
                }
                "search_files" -> {
                    val args = json.decodeFromString<SearchArgs>(call.arguments)
                    val path = path(args.path, root = true)
                    require(args.query.isNotEmpty() && args.query.length <= 256)
                    readOnly(call) {
                        val report = workspace.search(path, args.query)
                        success(call, buildJsonObject {
                            put("hits", buildJsonArray { report.hits.forEach { hit -> add(buildJsonObject {
                                put("path", hit.path); hit.line?.let { put("line", it) }; put("context", hit.context.take(240))
                            }) } })
                            put("limited", report.limited)
                        }, "Searched  \"${args.query.take(48)}\"")
                    }
                }
                "write_file" -> {
                    val args = json.decodeFromString<WriteArgs>(call.arguments)
                    val path = path(args.path); hash(args.expectedSha256)
                    mutation(call, "Edit", path) {
                        snapshot(call, workspace.write(path, args.content, args.expectedSha256), "Edited  $path")
                    }
                }
                "apply_patch" -> {
                    val args = json.decodeFromString<PatchArgs>(call.arguments)
                    val path = path(args.path); hash(args.expectedSha256)
                    require(args.edits.isNotEmpty() && args.edits.size <= 100)
                    val edits = args.edits.map { require(it.oldText.isNotEmpty()); AgentTextEdit(it.oldText, it.newText) }
                    mutation(call, "Edit", "$path\n${edits.size} exact replacement${if (edits.size == 1) "" else "s"}") {
                        snapshot(call, workspace.patch(path, args.expectedSha256, edits), "Edited  $path")
                    }
                }
                "create_file" -> {
                    val args = json.decodeFromString<PathArgs>(call.arguments); val path = path(args.path)
                    mutation(call, "Create file", path) {
                        snapshot(call, workspace.createFile(path), "Created  $path")
                    }
                }
                "create_directory" -> {
                    val args = json.decodeFromString<PathArgs>(call.arguments); val path = path(args.path)
                    mutation(call, "Create folder", path) {
                        workspace.createDirectory(path)
                        success(call, buildJsonObject { put("path", path); put("type", "directory") }, "Created  $path")
                    }
                }
                "rename_path" -> {
                    val args = json.decodeFromString<RenameArgs>(call.arguments)
                    val path = path(args.path); name(args.newName)
                    mutation(call, "Rename", "$path\n→ ${args.newName}") {
                        workspace.rename(path, args.newName)
                        success(call, buildJsonObject { put("path", path); put("new_name", args.newName) }, "Renamed  $path")
                    }
                }
                "move_path" -> {
                    val args = json.decodeFromString<MoveArgs>(call.arguments)
                    val path = path(args.path); val destination = path(args.destination, root = true)
                    mutation(call, "Move", "$path\n→ ${destination.ifEmpty { "." }}") {
                        workspace.move(path, destination)
                        success(call, buildJsonObject { put("path", path); put("destination", destination) }, "Moved  $path")
                    }
                }
                "delete_path" -> {
                    val args = json.decodeFromString<PathArgs>(call.arguments); val path = path(args.path)
                    mutation(call, "Delete", path) {
                        workspace.delete(path)
                        success(call, buildJsonObject { put("path", path); put("deleted", true) }, "Deleted  $path")
                    }
                }
                else -> invalid(call, "unknown_tool")
            }
        } catch (_: SerializationException) {
            invalid(call, "invalid_arguments")
        } catch (_: IllegalArgumentException) {
            invalid(call, "invalid_arguments")
        } catch (_: InvalidPath) {
            invalid(call, "invalid_path")
        }
    }

    private fun readOnly(call: AgentToolCall, action: suspend () -> AgentToolResult) =
        PreparedAgentTool(call, null) { execute(call, action) }

    private fun mutation(
        call: AgentToolCall,
        title: String,
        detail: String,
        action: suspend () -> AgentToolResult,
    ) = PreparedAgentTool(call, AgentApprovalRequest(call, title, detail)) { execute(call, action) }

    private suspend fun execute(call: AgentToolCall, action: suspend () -> AgentToolResult): AgentToolResult = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AgentWorkspaceFailure) {
        failure(call, e.code)
    } catch (_: Exception) {
        failure(call, "workspace_error")
    }

    private fun invalid(call: AgentToolCall, code: String) =
        PreparedAgentTool(call, null) { failure(call, code) }

    private fun failure(call: AgentToolCall, code: String) = AgentToolResult(
        call.id, call.name, buildJsonObject { put("error", code) }.toString(), true, "Failed  ${call.name}",
    )

    private fun success(call: AgentToolCall, value: JsonObject, summary: String) =
        AgentToolResult(call.id, call.name, value.toString(), summary = summary)

    private fun snapshot(call: AgentToolCall, file: AgentFileSnapshot, summary: String) = success(call, buildJsonObject {
        put("path", file.path); put("sha256", file.sha256); put("size", file.size)
    }, summary)

    private fun path(value: String, root: Boolean = false): String {
        if (value.isEmpty()) {
            if (root) return value
            throw InvalidPath()
        }
        if (value.length > 4096) throw InvalidPath()
        val segments = value.split('/')
        if (segments.size > 64 || segments.any { it.isEmpty() || it == "." || it == ".." || it.length > 255 ||
                it.any { char -> char == '\\' || char.isISOControl() } }) throw InvalidPath()
        return value
    }

    private fun name(value: String) {
        if (value.isEmpty() || value == "." || value == ".." || value.length > 255 ||
            value.any { it == '/' || it == '\\' || it.isISOControl() }) throw InvalidPath()
    }

    private fun hash(value: String) = require(HASH.matches(value))

    private class InvalidPath : Exception()

    @Serializable private data class ListArgs(val path: String = "")
    @Serializable private data class PathArgs(val path: String)
    @Serializable private data class SearchArgs(val query: String, val path: String = "")
    @Serializable private data class WriteArgs(
        val path: String,
        val content: String,
        @kotlinx.serialization.SerialName("expected_sha256") val expectedSha256: String,
    )
    @Serializable private data class PatchEdit(
        @kotlinx.serialization.SerialName("old_text") val oldText: String,
        @kotlinx.serialization.SerialName("new_text") val newText: String,
    )
    @Serializable private data class PatchArgs(
        val path: String,
        @kotlinx.serialization.SerialName("expected_sha256") val expectedSha256: String,
        val edits: List<PatchEdit>,
    )
    @Serializable private data class RenameArgs(
        val path: String,
        @kotlinx.serialization.SerialName("new_name") val newName: String,
    )
    @Serializable private data class MoveArgs(val path: String, val destination: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = false; isLenient = false }
        private val HASH = Regex("[0-9a-f]{64}")
        private const val MAX_ARGUMENT_BYTES = 1_200_000
        private const val MAX_LIST_ENTRIES = 200

        private fun schema(required: List<String>, vararg fields: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { fields.forEach { (name, value) -> put(name, value) } })
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
        private val string = buildJsonObject { put("type", "string") }
        private val path = "path" to string

        val definitions = listOf(
            AgentToolDefinition("list_directory", "List a workspace directory.", schema(emptyList(), path)),
            AgentToolDefinition("read_file", "Read a UTF-8 workspace file with its SHA-256 hash.", schema(listOf("path"), path)),
            AgentToolDefinition("search_files", "Search bounded workspace paths and text.", schema(listOf("query"), path, "query" to string)),
            AgentToolDefinition("write_file", "Replace an existing text file when its hash still matches.",
                schema(listOf("path", "content", "expected_sha256"), path, "content" to string, "expected_sha256" to string)),
            AgentToolDefinition("apply_patch", "Apply exact unique text replacements when the file hash still matches.",
                schema(listOf("path", "expected_sha256", "edits"), path, "expected_sha256" to string,
                    "edits" to buildJsonObject { put("type", "array"); put("items", schema(listOf("old_text", "new_text"), "old_text" to string, "new_text" to string)) })),
            AgentToolDefinition("create_file", "Create a new empty text file.", schema(listOf("path"), path)),
            AgentToolDefinition("create_directory", "Create a new directory.", schema(listOf("path"), path)),
            AgentToolDefinition("rename_path", "Rename an existing file or directory.", schema(listOf("path", "new_name"), path, "new_name" to string)),
            AgentToolDefinition("move_path", "Move a path into an existing directory.", schema(listOf("path", "destination"), path, "destination" to string)),
            AgentToolDefinition("delete_path", "Delete an existing non-root path.", schema(listOf("path"), path)),
        )
    }
}
