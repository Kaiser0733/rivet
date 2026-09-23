package com.kaiser.rivet.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object AgentToolError {
    private val actions = mapOf(
        "terminal_active" to "Stop the interactive Terminal before using agent tools or commands.",
        "sync_required" to "Sync or resolve pending Terminal workspace changes before continuing.",
        "denied" to "The user denied this mutation. Do not retry it without a new request.",
        "checkpoint_unavailable" to "Resolve workspace or storage changes before retrying the mutation.",
    )
    private val deterministic = setOf("terminal_active", "sync_required", "denied",
        "checkpoint_unavailable", "invalid_arguments", "invalid_path", "unknown_tool")

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
        val code = runCatching {
            Json.parseToJsonElement(result.content).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
        return code?.takeIf { it in deterministic }
    }

    fun action(code: String): String? = actions[code]
}
