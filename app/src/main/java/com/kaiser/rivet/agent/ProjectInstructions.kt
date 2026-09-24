package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import java.util.TreeSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class ProjectInstructionSet(val text: String, val files: List<String>, val limited: Boolean)

/** Loads only the directory chains the agent has touched, inside the selected SAF tree. */
class ProjectInstructions(private val workspace: SafWorkspace) {
    suspend fun load(targets: Map<WorkspacePath, Boolean>): ProjectInstructionSet {
        val directories = TreeSet<WorkspacePath>(compareBy<WorkspacePath> { it.segments.size }.thenBy { it.value })
        directories += WorkspacePath.ROOT
        var limited = false
        targets.forEach { (target, directory) ->
            val segments = (if (directory) target else target.parent()).segments
            if (segments.size > MAX_DEPTH) limited = true
            var current = WorkspacePath.ROOT
            segments.take(MAX_DEPTH).forEach { segment ->
                current = current.child(segment)
                directories += current
            }
        }
        val sections = mutableListOf<String>()
        val files = mutableListOf<String>()
        var bytes = 0
        if (directories.size > MAX_DIRECTORIES) limited = true
        for (directory in directories.take(MAX_DIRECTORIES)) {
            val file = directory.child("AGENTS.md")
            val content = try { workspace.readProjectInstruction(file, PER_FILE_BYTES) }
                catch (error: WorkspaceFailure) {
                    when (error.reason) {
                        WorkspaceFailure.Reason.MISSING, WorkspaceFailure.Reason.NOT_DIRECTORY -> continue
                        WorkspaceFailure.Reason.TOO_LARGE -> {
                            limited = true
                            sections += "[${file.value}: skipped; exceeds $PER_FILE_BYTES bytes]"
                        }
                        else -> sections += "[${file.value}: unreadable]"
                    }
                    continue
                }
            if (files.size >= MAX_FILES || bytes + content.toByteArray(Charsets.UTF_8).size > TOTAL_BYTES) {
                limited = true
                sections += "[${file.value}: skipped; project instruction limit reached]"
                continue
            }
            files += file.value
            bytes += content.toByteArray(Charsets.UTF_8).size
            sections += "${file.value}:\n$content"
        }
        return ProjectInstructionSet(sections.joinToString("\n\n"), files, limited)
    }

    fun targets(call: AgentToolCall): Map<WorkspacePath, Boolean> {
        if (call.name !in PATH_TOOLS || call.arguments.length > MAX_ARGUMENT_CHARS) return emptyMap()
        val fields = try { Json.parseToJsonElement(call.arguments).jsonObject }
            catch (_: Exception) { return emptyMap() }
        val paths = linkedMapOf<WorkspacePath, Boolean>()
        for (key in listOf("path", "destination", "cwd")) {
            val raw = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val path = try { WorkspacePath.parse(raw) } catch (_: WorkspaceFailure) { continue }
            paths[path] = key != "path" || call.name in DIRECTORY_TOOLS
        }
        return paths
    }

    companion object {
        const val PER_FILE_BYTES = 8 * 1024
        const val TOTAL_BYTES = 32 * 1024
        const val MAX_FILES = 8
        private const val MAX_DEPTH = 32
        private const val MAX_DIRECTORIES = 64
        private const val MAX_ARGUMENT_CHARS = 1_200_000
        private val PATH_TOOLS = setOf("read_file", "list_directory", "search_files", "write_file",
            "apply_patch", "create_file", "create_directory", "rename_path", "move_path",
            "delete_path", "run_command", "git_diff")
        private val DIRECTORY_TOOLS = setOf("list_directory", "search_files", "create_directory")
    }
}
