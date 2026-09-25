package com.kaiser.rivet.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object AgentToolError {
    private val actions = mapOf(
        "terminal_active" to "The command runner is busy. Try again after its state changes.",
        "sync_required" to "Rivet could not safely reconcile project changes. Stop and review the project before retrying.",
        "workspace_unavailable" to "Choose the project again before retrying the command.",
        "workspace_changed" to "The selected project changed. Start a new request in the current project.",
        "runtime_unavailable" to "Rivet could not start the command runner. Try again.",
        "denied" to "The user denied this mutation. Do not retry it without a new request.",
        "checkpoint_unavailable" to "Resolve workspace or storage changes before retrying the mutation.",
        "interrupted" to "Inspect workspace state before retrying; the operation outcome is unknown.",
        "unsafe_entry" to "Rivet found a project item it could not safely access. Remove or rename it before retrying.",
    )
    private val deterministic = setOf("terminal_active", "sync_required", "workspace_unavailable",
        "workspace_changed", "runtime_unavailable", "denied", "checkpoint_unavailable",
        "invalid_arguments", "invalid_path", "unknown_tool", "unsafe_entry")
    private val runtimeStops = setOf("terminal_active", "sync_required", "workspace_unavailable",
        "workspace_changed", "runtime_unavailable", "checkpoint_unavailable", "interrupted",
        "materialize_failed", "baseline_invalid", "storage", "mirror_dirty", "unsafe_entry")

    fun content(code: String): String = buildJsonObject {
        put("error", code)
        actions[code]?.let {
            put("retryable", false)
            put("required_action", it)
        }
    }.toString()

    fun noProgress(code: String): String = buildJsonObject {
        put("error", "no_progress")
        put("reason", code)
        put("retryable", false)
        put("required_action", actions[code] ?: "Change the request or relevant workspace state before retrying.")
    }.toString()

    fun deterministicCode(result: AgentToolResult): String? {
        if (!result.error) return null
        val code = code(result)
        return code?.takeIf { it in deterministic }
    }

    fun runtimeStopCode(result: AgentToolResult): String? {
        val value = runCatching { Json.parseToJsonElement(result.content).jsonObject }.getOrNull()
            ?: return null
        val code = if (result.error) value["error"]?.jsonPrimitive?.content else null
        if (code != null && code in runtimeStops) return code
        if (result.name == "run_command") return when (value["sync"]?.jsonPrimitive?.content) {
            "conflict" -> "sync_conflict"
            "failed" -> "sync_failed"
            "interrupted" -> "sync_interrupted"
            else -> null
        }
        return null
    }

    private fun code(result: AgentToolResult): String? = runCatching {
        Json.parseToJsonElement(result.content).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    fun action(code: String): String? = actions[code]
}
