package com.kaiser.rivet.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

enum class ActivityOutcome { Queued, WaitingApproval, Running, Completed, Failed, Denied, Blocked, Cancelled, Unknown }

data class AgentActivityOperation(
    val title: String,
    val detail: String,
    val outcome: ActivityOutcome,
    val outcomeDetail: String? = null,
)

data class AgentActivityGroup(
    val id: String,
    val summary: String,
    val operations: List<AgentActivityOperation>,
)

sealed interface AgentConversationItem {
    data class Message(val message: AgentMessage) : AgentConversationItem
    data class Activity(val group: AgentActivityGroup) : AgentConversationItem
}

/** Projects correlated transcript events and temporary lifecycle telemetry into display-only activity. */
object AgentActivityProjection {
    private val json = Json { ignoreUnknownKeys = true }

    fun conversation(
        messages: List<AgentMessage>,
        liveEvents: List<AgentToolLifecycle> = emptyList(),
    ): List<AgentConversationItem> {
        val live = liveEvents.associateBy { it.correlationKey }
        val nextToolIndex = IntArray(messages.size)
        var nearestToolIndex = -1
        for (index in messages.indices.reversed()) {
            nextToolIndex[index] = nearestToolIndex
            if (messages[index].role == AgentRole.Tool) nearestToolIndex = index
        }
        val items = mutableListOf<AgentConversationItem>()
        messages.forEachIndexed { index, message ->
            when (message.role) {
                AgentRole.User -> items += AgentConversationItem.Message(message)
                AgentRole.Assistant -> {
                    if (message.text.isNotBlank()) items += AgentConversationItem.Message(
                        AgentMessage.assistant(message.text, transportState = message.transportState))
                    if (message.toolCalls.isNotEmpty()) {
                        val resultIndex = nextToolIndex[index]
                        val correlated = if (resultIndex < 0) emptyMap()
                            else messages[resultIndex].toolResults.associateBy { it.callId }
                        val operations = message.toolCalls.map { call ->
                            operation(call, correlated[call.id], live[AgentToolLifecycle.keyFor(call.id)])
                        }
                        items += AgentConversationItem.Activity(AgentActivityGroup(
                            id = AgentToolLifecycle.groupKeyFor(message.toolCalls.asSequence().map { it.id }),
                            summary = summarize(operations),
                            operations = operations,
                        ))
                    }
                }
                AgentRole.Tool -> Unit
            }
        }
        return items
    }

    private fun operation(
        call: AgentToolCall,
        result: AgentToolResult?,
        live: AgentToolLifecycle?,
    ): AgentActivityOperation {
        val args = objectValue(call.arguments)
        val value = result?.let { objectValue(it.content) }.orEmpty()
        val title = title(call.name)
        val detail = detail(call.name, args, value)
        val outcome = result?.let { resultOutcome(it, value) } ?: live?.stage?.let(::liveOutcome)
            ?: ActivityOutcome.Unknown
        val exit = value["exit_code"].primitiveContent()?.toIntOrNull()
        val outcomeDetail = when {
            call.name == "run_command" && exit != null -> "Exit $exit"
            live?.stage == AgentToolLifecycleStage.AwaitingApproval -> "Waiting for approval"
            result == null && live == null -> "Outcome unknown"
            else -> null
        }
        return AgentActivityOperation(title, detail, outcome, outcomeDetail)
    }

    private fun resultOutcome(result: AgentToolResult, value: Map<String, kotlinx.serialization.json.JsonElement>): ActivityOutcome {
        val error = value["error"].primitiveContent()
        if (result.error) return when (error) {
            "denied" -> ActivityOutcome.Denied
            "cancelled", "interrupted" -> ActivityOutcome.Cancelled
            "not_executed", "no_progress", "session_limit", "runaway_guard", "workspace_unavailable",
            "workspace_changed", "sync_required", "runtime_unavailable", "checkpoint_unavailable",
            "unsafe_entry", "process_limit", "foreground_service_unavailable", "preview_unavailable" ->
                ActivityOutcome.Blocked
            else -> ActivityOutcome.Failed
        }
        if (result.name == "run_command") {
            val exit = value["exit_code"].primitiveContent()?.toIntOrNull()
            val sync = value["sync"].primitiveContent()
            if (exit != null && exit != 0 || sync in setOf("conflict", "failed", "interrupted", "pending")) {
                return ActivityOutcome.Failed
            }
        }
        return ActivityOutcome.Completed
    }

    private fun liveOutcome(stage: AgentToolLifecycleStage) = when (stage) {
        AgentToolLifecycleStage.Queued -> ActivityOutcome.Queued
        AgentToolLifecycleStage.AwaitingApproval -> ActivityOutcome.WaitingApproval
        AgentToolLifecycleStage.Started -> ActivityOutcome.Running
        AgentToolLifecycleStage.Completed -> ActivityOutcome.Completed
        AgentToolLifecycleStage.Failed -> ActivityOutcome.Failed
        AgentToolLifecycleStage.Denied -> ActivityOutcome.Denied
        AgentToolLifecycleStage.Blocked -> ActivityOutcome.Blocked
        AgentToolLifecycleStage.Cancelled -> ActivityOutcome.Cancelled
    }

    private fun detail(
        name: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        result: Map<String, kotlinx.serialization.json.JsonElement>,
    ): String {
        fun arg(key: String) = args[key].primitiveContent()
        fun output(key: String) = result[key].primitiveContent()
        val path = output("path") ?: arg("path")
        val text = when (name) {
            "read_file", "list_directory", "write_file", "apply_patch", "create_file", "create_directory",
            "delete_path", "download_file" -> path ?: ""
            "search_files" -> arg("query")?.let { "\"$it\"${path?.let { p -> " in $p" }.orEmpty()}" }.orEmpty()
            "git_status" -> "Git status"
            "git_diff" -> path?.takeIf(String::isNotEmpty) ?: "Project changes"
            "rename_path" -> "${arg("path").orEmpty()} → ${output("path") ?: arg("new_name").orEmpty()}"
            "move_path" -> "${arg("path").orEmpty()} → ${output("path") ?: arg("destination").orEmpty()}"
            "run_command" -> arg("command").orEmpty()
            "start_preview" -> output("entry") ?: arg("entry").orEmpty()
            "list_processes" -> result["processes"]?.let { "${runCatching { it.jsonArray.size }.getOrDefault(0)} active" }.orEmpty()
            else -> ""
        }
        return safeText(text, if (name == "run_command") MAX_COMMAND_DISPLAY_CHARS else MAX_DETAIL_CHARS)
    }

    private fun title(name: String): String = when (name) {
        "read_file" -> "Read"
        "list_directory" -> "List"
        "search_files" -> "Search"
        "git_status", "git_diff" -> "Inspect Git"
        "write_file", "apply_patch" -> "Edit"
        "create_file", "create_directory" -> "Create"
        "rename_path" -> "Rename"
        "move_path" -> "Move"
        "delete_path" -> "Delete"
        "run_command" -> "Run"
        "download_file" -> "Download"
        "start_preview" -> "Preview"
        "list_processes" -> "List processes"
        "stop_process" -> "Stop process"
        else -> "Project operation"
    }

    private fun summarize(operations: List<AgentActivityOperation>): String {
        val counts = linkedMapOf<Pair<String, ActivityOutcome>, Int>()
        operations.forEach { operation ->
            val key = operation.title to operation.outcome
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.map { (key, count) ->
            val (title, outcome) = key
            val (verb, unit) = when (title) {
                "Read" -> "Read" to "file"
                "List" -> "Listed" to "folder"
                "Search" -> "Searched" to "search"
                "Inspect Git" -> "Inspected Git" to "operation"
                "Edit" -> "Edited" to "file"
                "Create" -> "Created" to "item"
                "Delete" -> "Deleted" to "item"
                "Rename" -> "Renamed" to "item"
                "Move" -> "Moved" to "item"
                "Run" -> "Ran" to "command"
                "Download" -> "Downloaded" to "file"
                "Preview" -> "Started" to "preview"
                "List processes" -> "Listed" to "process list"
                "Stop process" -> "Stopped" to "process"
                else -> "Handled" to "operation"
            }
            val plural = if (count == 1) unit else when (unit) {
                "file" -> "files"
                "folder" -> "folders"
                "search" -> "searches"
                "operation" -> "operations"
                "command" -> "commands"
                "item" -> "items"
                "preview" -> "previews"
                "process list" -> "process lists"
                "process" -> "processes"
                else -> "operations"
            }
            if (outcome == ActivityOutcome.Completed) "$verb $count $plural"
            else "$title $count $plural (${outcome.label()})"
        }.joinToString(" · ")
    }

    private fun ActivityOutcome.label() = when (this) {
        ActivityOutcome.Queued -> "Queued"
        ActivityOutcome.WaitingApproval -> "Waiting for approval"
        ActivityOutcome.Running -> "Running"
        ActivityOutcome.Completed -> "Completed"
        ActivityOutcome.Failed -> "Failed"
        ActivityOutcome.Denied -> "Denied"
        ActivityOutcome.Blocked -> "Stopped"
        ActivityOutcome.Cancelled -> "Cancelled"
        ActivityOutcome.Unknown -> "Outcome unknown"
    }

    private fun objectValue(value: String): Map<String, kotlinx.serialization.json.JsonElement> = try {
        json.parseToJsonElement(value).jsonObject
    } catch (_: SerializationException) { emptyMap() }
    catch (_: IllegalArgumentException) { emptyMap() }

    private fun JsonElement?.primitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun safeText(value: String, limit: Int): String {
        val sanitized = value
            .replace(Regex("(?i)(api[_-]?key|token|password|secret)(\\s*[:=]\\s*)[^\\s]+"), "$1$2[redacted]")
            .replace(Regex("(?i)bearer\\s+[^\\s]+"), "Bearer [redacted]")
            .replace(Regex("(?i)(https?://)[^/@\\s]+@"), "$1[redacted]@")
            .replace(Regex("(?i)(?:--user(?:name)?|--password)(\\s+)[^\\s]+"), "$1[redacted]")
            .replace(Regex("(?i)(?:^|\\s)-u(\\s+)[^\\s]+"), " -u$1[redacted]")
            .replace(Regex("\\bsk-[A-Za-z0-9_-]{12,}"), "[redacted]")
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
        return sanitized.take(limit).dropLastWhile { it.isHighSurrogate() }
    }

    private const val MAX_DETAIL_CHARS = 180
    private const val MAX_COMMAND_DISPLAY_CHARS = 260
}
