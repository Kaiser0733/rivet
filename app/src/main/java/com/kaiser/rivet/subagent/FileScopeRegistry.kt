package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The coordinator is a writer too; its key is fixed so worker ids cannot spoof it. */
data class FileScope(
    val writer: String,
    val job: String,
    val paths: Set<WorkspacePath>,
)

/**
 * Ownership is structural for a job's lifetime. A worker's empty scope is a
 * read-only job: any mutation target is outside it. Paths are component-aware,
 * so "src" covers "src/a.kt" but never "src2/a.kt".
 */
class FileScopeRegistry {
    private val scopes = linkedMapOf<String, FileScope>()

    fun register(writer: String, job: String, paths: Collection<WorkspacePath>) {
        require(writer.isNotBlank()) { "writer is required" }
        require(writer != COORDINATOR) { "the coordinator is not a worker scope" }
        scopes[writer] = FileScope(writer, job, paths.toSet())
    }

    fun release(writer: String) {
        scopes.remove(writer)
    }

    fun clear() = scopes.clear()

    fun scopeOf(writer: String): FileScope? = scopes[writer]

    fun liveScopes(): List<FileScope> = scopes.values.toList()

    /**
     * Returns null when the write is allowed, or one of the two stable tool-error
     * codes the coordinator/worker loop can surface.
     */
    fun checkWrite(writer: String, paths: Collection<WorkspacePath>): String? {
        if (paths.isEmpty()) return null
        if (writer != COORDINATOR) {
            val own = scopes[writer] ?: return OUTSIDE_FILE_SCOPE
            if (paths.any { target -> own.paths.none { target.isWithin(it) } }) {
                return OUTSIDE_FILE_SCOPE
            }
        }
        for (scope in scopes.values) {
            if (scope.writer == writer || scope.paths.isEmpty()) continue
            if (paths.any { target -> scope.paths.any { owned -> overlapsOne(target, owned) } }) {
                return "$OWNED_BY_WORKER_PREFIX${scope.job}"
            }
        }
        return null
    }

    /** Move destinations are checked in addition to the source path. */
    fun checkWrite(writer: String, call: AgentToolCall): String? =
        checkWrite(writer, mutationPaths(call))

    companion object {
        const val COORDINATOR = "coordinator"
        const val OUTSIDE_FILE_SCOPE = "outside_file_scope"
        const val OWNED_BY_WORKER_PREFIX = "owned_by_worker:"

        fun overlaps(scopeA: Collection<WorkspacePath>, scopeB: Collection<WorkspacePath>): Boolean =
            scopeA.any { a -> scopeB.any { b -> overlapsOne(a, b) } }

        private fun overlapsOne(a: WorkspacePath, b: WorkspacePath): Boolean =
            a.isWithin(b) || b.isWithin(a)

        internal fun mutationPaths(call: AgentToolCall): List<WorkspacePath> {
            if (call.arguments.length > MAX_ARGUMENT_CHARS) return emptyList()
            val fields = try { Json.parseToJsonElement(call.arguments).jsonObject }
            catch (_: Exception) { return emptyList() }
            val result = linkedSetOf<WorkspacePath>()
            var source: WorkspacePath? = null
            val rawPath = (fields["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (rawPath != null) {
                val parsed = try { WorkspacePath.parse(rawPath) } catch (_: WorkspaceFailure) { null }
                if (parsed != null) {
                    source = parsed
                    result += parsed
                }
            }
            val rawDest = (fields["destination"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (rawDest != null) {
                val parsed = try { WorkspacePath.parse(rawDest) } catch (_: WorkspaceFailure) { null }
                if (parsed != null) {
                    result += if (call.name == "move_path" && parsed.isRoot && source != null && !source.isRoot) {
                        try { parsed.child(source.name) } catch (_: WorkspaceFailure) { parsed }
                    } else {
                        parsed
                    }
                }
            }
            if (call.name == "rename_path" && source != null && !source.isRoot) {
                val newName = (fields["new_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!newName.isNullOrBlank()) {
                    try {
                        result += source.parent().child(newName)
                    } catch (_: WorkspaceFailure) {
                    }
                }
            }
            return result.toList()
        }

        private const val MAX_ARGUMENT_CHARS = 1_200_000
    }
}
