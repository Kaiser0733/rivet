package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.WorkspaceFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object AgentToolError {
    // Every WorkspaceFailure.Reason already carries a complete explanation. Derive
    // the entries from the reason itself so a new reason cannot reach the model as a
    // bare code, which is what made the model retry the same edit until the runaway
    // guard fired.
    private val workspaceActions = WorkspaceFailure.Reason.entries.associate {
        it.name.lowercase() to it.message
    }
    // A genuine retry can clear these. Everything else describes the identical call
    // failing in the identical way, which is what the no-progress guard catches.
    private val transientWorkspaceCodes = setOf("provider", "write")

    private val actions = workspaceActions + mapOf(
        "sync_required" to "Rivet could not safely reconcile project changes. Stop and review the project before retrying.",
        "workspace_unavailable" to "Choose the project again before retrying the command.",
        "workspace_changed" to "The selected project changed. Start a new request in the current project.",
        "runtime_unavailable" to "Rivet could not start the command runner. Try again.",
        "denied" to "The user denied this mutation. Do not retry it without a new request.",
        "checkpoint_unavailable" to "Rivet's Undo covers up to 50,000 items and 1 GiB per project. If this project is larger, add the biggest generated folder to .rivetignore and retry.",
        "checkpoint_limit" to "Rivet's Undo covers up to 50,000 items and 1 GiB per project. Add the biggest generated folder to .rivetignore and retry.",
        "interrupted" to "Inspect workspace state before retrying; the operation outcome is unknown.",
        "unsafe_entry" to "Rivet found a project item it could not safely access. Remove or rename it before retrying.",
        "unsupported_system_management" to "Rivet cannot install apps, packages, or other Android system software.",
        "secret" to "Secrets never go into project memory. Remove the credential and record only a durable, committable fact.",
        "garden_first" to "Project memory is at its hard cap. Garden it first: merge related facts and delete stale lines.",
        "empty" to "The memory fact was empty. Write one durable sentence.",
        "too_long" to "The memory fact is too long. Keep one fact under 500 characters.",
        "approval_unavailable" to "Rivet could not safely request confirmation for this operation, so it stopped.",
        "process_limit" to "Rivet already has the maximum number of active project operations. Stop one in Processes and retry.",
        "thermal_blocked" to "The phone is too hot. Wait for it to cool before retrying, and do not retry the same long command now.",
        "battery_low" to "Battery is low — terminal paused for long jobs. Charge the phone or run a short command.",
        "foreground_service_unavailable" to "Rivet couldn't keep the local preview running. Try again while Rivet is open.",
        "preview_unavailable" to "Rivet couldn't start a local preview for this project. Check the selected files and try again.",
        "process_unavailable" to "That Rivet process is no longer active. List active processes before trying again.",
        "invalid_url" to "Rivet only downloads from valid HTTPS addresses without embedded credentials.",
        "redirect_blocked" to "Rivet stopped the download because the redirect was not allowed.",
        "download_too_large" to "This download exceeds Rivet's 64 MiB limit. Choose a smaller file.",
        "download_sha_mismatch" to "The downloaded file did not match the expected checksum. No project file was changed.",
        "destination_exists" to "That project file already exists. Read it first and provide its current hash before replacing it.",
        "download_failed" to "The server could not provide this file. Check the address and try again.",
        "network_error" to "Rivet couldn't reach the download server. Check the address and connection, then try again.",
        "invalid_checksum" to "Use a 64-character SHA-256 checksum or leave the checksum blank.",
        "invalid_offset" to "Read the file from a different offset. This one is past the end or splits a character.",
        "output_limit" to "Ask for a smaller part of this file. The requested range is too large for one result.",
    )
    private val deterministic = (workspaceActions.keys - transientWorkspaceCodes) + setOf(
        "sync_required", "workspace_unavailable",
        "workspace_changed", "runtime_unavailable", "denied", "checkpoint_unavailable",
        "invalid_arguments", "invalid_path", "unknown_tool", "unsafe_entry", "stale_target",
        "unsupported_system_management", "approval_unavailable", "process_limit",
        "thermal_blocked", "battery_low",
        "foreground_service_unavailable", "preview_unavailable", "process_unavailable",
        "invalid_url", "redirect_blocked", "download_too_large", "download_sha_mismatch",
        "destination_exists", "invalid_checksum", "skill_not_found", "skill_manual_only",
        "secret", "garden_first", "empty", "too_long")
    private val runtimeStops = setOf("sync_required", "workspace_unavailable",
        "workspace_changed", "runtime_unavailable", "checkpoint_unavailable", "interrupted",
        "materialize_failed", "baseline_invalid", "storage", "mirror_dirty", "unsafe_entry", "process_limit",
        "foreground_service_unavailable", "preview_unavailable")

    fun content(code: String, statusCode: Int? = null, subject: String? = null): String = buildJsonObject {
        put("error", code)
        actions[code]?.let {
            put("retryable", false)
            put("required_action", it)
        }
        statusCode?.let { put("status_code", it) }
        // The path that blocked the call, when the failure has one.
        subject?.let { put("path", it) }
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
