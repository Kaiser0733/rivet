package com.kaiser.rivet.subagent

import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The validated spawn arguments, rendered into the worker's first user message. */
data class WorkerBrief(
    val job: String,
    val brief: String,
    val writeScope: List<WorkspacePath> = emptyList(),
    val skill: String? = null,
    val retryOf: String? = null,
) {
    fun render(): String = buildString {
        appendLine("You are a Rivet worker for one job.")
        appendLine()
        appendLine("Job: $job")
        appendLine()
        appendLine("Brief:")
        appendLine(brief.trim())
        appendLine()
        appendLine("Write scope: " +
            if (writeScope.isEmpty()) "read-only" else writeScope.joinToString(", ") { it.value })
        if (skill != null) appendLine("Skill: $skill")
        if (retryOf != null) appendLine("Retry of: $retryOf")
        appendLine()
        appendLine("Return format:")
        appendLine("Summary: what you did or found, in a short paragraph.")
        appendLine("Findings: concrete facts or artefacts, one per line.")
        appendLine("Open questions: anything the coordinator must decide or verify.")
        appendLine()
        appendLine("Claim that tests or builds passed only when an observed command result says so.")
        append("You cannot start workers.")
    }

    fun validationError(): String? {
        if (job.isBlank()) return "job is required"
        if (brief.isBlank()) return "brief is required"
        if (job.length > MAX_JOB_CHARS) return "job must be at most $MAX_JOB_CHARS characters"
        if (brief.toByteArray(Charsets.UTF_8).size > MAX_BRIEF_BYTES) {
            return "brief must be at most $MAX_BRIEF_BYTES UTF-8 bytes"
        }
        if (writeScope.size > MAX_SCOPE_PATHS) {
            return "write_scope must contain at most $MAX_SCOPE_PATHS paths"
        }
        return null
    }

    companion object {
        const val MAX_JOB_CHARS = 200
        const val MAX_BRIEF_BYTES = 8 * 1024
        const val MAX_SCOPE_PATHS = 32

        fun parse(arguments: String): BriefParse {
            val fields = try {
                Json.parseToJsonElement(arguments).jsonObject
            } catch (_: Exception) {
                return BriefParse.Invalid("spawn arguments must be a JSON object")
            }
            val job = stringField(fields["job"])
                ?: return BriefParse.Invalid("job is required")
            val brief = stringField(fields["brief"])
                ?: return BriefParse.Invalid("brief is required")
            val rawSkill = fields["skill"]
            val skill = if (rawSkill == null || rawSkill is JsonNull) null
            else stringField(rawSkill) ?: return BriefParse.Invalid("skill must be a string")
            val rawRetry = fields["retry_of"]
            val retryOf = if (rawRetry == null || rawRetry is JsonNull) null
            else stringField(rawRetry) ?: return BriefParse.Invalid("retry_of must be a string")
            val rawScope = fields["write_scope"]
            val scope = when {
                rawScope == null -> emptyList()
                rawScope !is JsonArray -> return BriefParse.Invalid("write_scope must be an array of paths")
                else -> {
                    val paths = mutableListOf<WorkspacePath>()
                    for (element in rawScope) {
                        val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: return BriefParse.Invalid("write_scope entries must be path strings")
                        val path = try { WorkspacePath.parse(raw) } catch (_: WorkspaceFailure) {
                            return BriefParse.Invalid("invalid write_scope path '$raw'")
                        }
                        if (path !in paths) paths += path
                    }
                    paths
                }
            }
            val worker = WorkerBrief(job.trim(), brief, scope, skill?.trim()?.takeIf { it.isNotEmpty() },
                retryOf?.trim()?.takeIf { it.isNotEmpty() })
            worker.validationError()?.let { return BriefParse.Invalid(it) }
            return BriefParse.Valid(worker)
        }

        private fun stringField(value: Any?): String? =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    }
}

sealed interface BriefParse {
    data class Valid(val brief: WorkerBrief) : BriefParse
    data class Invalid(val line: String) : BriefParse
}
