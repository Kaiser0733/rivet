package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.RunPhase
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class FileChangeKind(val wire: String) {
    Added("added"),
    Modified("modified"),
    Deleted("deleted"),
}

data class ChangedFile(
    val path: String,
    val kind: FileChangeKind,
    val additions: Int = 0,
    val deletions: Int = 0,
)

data class ObservedCommand(
    val command: String,
    val exitCode: Int?,
    val sync: String? = null,
)

/**
 * The runtime-assembled result. Ground truth lives under `verified`; the
 * worker's prose lives under `claimed` and can never overwrite it.
 */
object ResultEnvelope {
    const val MAX_DIFF_BYTES = WorkerDiff.MAX_BYTES
    const val MAX_COMMAND_CHARS = 120

    fun build(
        workerId: String,
        state: RunPhase,
        claim: WorkerClaim,
        stopReason: String? = null,
        error: String? = null,
        files: List<ChangedFile> = emptyList(),
        outOfScopeChanges: List<String> = emptyList(),
        commands: List<ObservedCommand> = emptyList(),
        diff: WorkerDiff? = null,
    ): String {
        val claimedSummary = if (state == RunPhase.Failed && error != null) error
        else claim.summary
        val verified = buildJsonObject {
            put("state", state.name.lowercase(Locale.ROOT))
            put("stop_reason", stopReason?.let { JsonPrimitive(it) } ?: JsonNull)
            put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
            put("files_changed", buildJsonArray {
                files.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("kind", file.kind.wire)
                        put("+", file.additions)
                        put("-", file.deletions)
                    })
                }
            })
            put("out_of_scope_changes", buildJsonArray {
                outOfScopeChanges.forEach { add(JsonPrimitive(it)) }
            })
            put("commands", buildJsonArray {
                commands.forEach { command ->
                    add(buildJsonObject {
                        put("command", command.command.take(MAX_COMMAND_CHARS))
                        put("exit_code", command.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("sync", command.sync?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
            put("diff", diff?.text.orEmpty())
            put("truncated", diff?.truncated == true)
        }
        val claimed = buildJsonObject {
            put("summary", claimedSummary)
            put("findings", buildJsonArray {
                claim.findings.forEach { add(JsonPrimitive(it)) }
            })
            put("open_questions", buildJsonArray {
                claim.openQuestions.forEach { add(JsonPrimitive(it)) }
            })
            put("truncated", claim.truncated)
        }
        return buildJsonObject {
            put("worker_id", workerId)
            put("verified", verified)
            put("claimed", claimed)
        }.toString()
    }

    /** Convenience for callers that need the parsed JSON without a second codec. */
    fun fromJson(text: String) = Json.parseToJsonElement(text)
}
