package com.kaiser.rivet.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

@Serializable
enum class AgentRole {
    @SerialName("user") User,
    @SerialName("assistant") Assistant,
    @SerialName("tool") Tool,
    @SerialName("context") Context,
}

@Serializable
data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class AgentToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val error: Boolean = false,
    val summary: String = name,
)

@Serializable
data class AgentMessage(
    val role: AgentRole,
    val text: String = "",
    val toolCalls: List<AgentToolCall> = emptyList(),
    val toolResults: List<AgentToolResult> = emptyList(),
    val transportState: String? = null,
    val internalContext: InternalContext? = null,
) {
    companion object {
        fun user(text: String) = AgentMessage(AgentRole.User, text = text)

        fun assistant(
            text: String,
            toolCalls: List<AgentToolCall> = emptyList(),
            transportState: String? = null,
        ) = AgentMessage(
            AgentRole.Assistant,
            text = text,
            toolCalls = toolCalls,
            transportState = transportState,
        )

        fun tools(results: List<AgentToolResult>) =
            AgentMessage(AgentRole.Tool, toolResults = results)
    }
}

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class AgentResponse(
    val text: String = "",
    val toolCalls: List<AgentToolCall> = emptyList(),
    val transportState: String? = null,
    val usage: AgentUsage? = null,
)

data class AgentUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val totalTokens: Long? = null,
    val cacheCreationTokens: Long? = null,
)

data class AgentApprovalRequest(
    val call: AgentToolCall,
    val title: String,
    val detail: String,
    val destructivePath: String? = null,
    val dangerous: Boolean = false,
    val approvalToken: Long = 0L,
)

enum class AutonomyMode { Ask, BasicYolo, Yolo }

/** Execution effects drive integrity work independently from human confirmation. */
enum class AgentToolEffect(
    val changesWorkspace: Boolean = false,
    val requiresCheckpoint: Boolean = false,
    val requiresRuntimeBlocker: Boolean = false,
) {
    ReadOnly,
    WorkspaceMutation(true, true),
    DestructiveWorkspaceMutation(true, true),
    ForegroundCommand(true, true, true),
    Download(true, true),
    PersistentProcess,
    Preview(false, false, true),
}

enum class BasicAutonomyRisk { Routine, Elevated }

enum class AgentToolLifecycleStage {
    Queued, AwaitingApproval, Started, Completed, Failed, Denied, Blocked, Cancelled,
}

data class AgentToolLifecycle(
    val correlationKey: String,
    val stage: AgentToolLifecycleStage,
    val changedAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        fun keyFor(callId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(callId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        fun groupKeyFor(callIds: Sequence<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            callIds.forEach { id ->
                digest.update(id.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

class PreparedAgentTool(
    val call: AgentToolCall,
    /** Optional human-readable confirmation details, never mutation authority. */
    val approval: AgentApprovalRequest?,
    val resultContentLimitBytes: Int = AgentLoop.MAX_TOOL_RESULT_BYTES,
    val effect: AgentToolEffect = defaultEffect(call.name),
    val basicAutonomyRisk: BasicAutonomyRisk = BasicAutonomyRisk.Elevated,
    val modelRequestsApproval: Boolean = false,
    val blockedReason: String? = null,
    val execute: suspend () -> AgentToolResult,
)

private fun defaultEffect(name: String): AgentToolEffect = when (name) {
    "write_file", "apply_patch", "create_file", "create_directory" -> AgentToolEffect.WorkspaceMutation
    "rename_path", "move_path", "delete_path" -> AgentToolEffect.DestructiveWorkspaceMutation
    "run_command" -> AgentToolEffect.ForegroundCommand
    "download_file" -> AgentToolEffect.Download
    "start_preview" -> AgentToolEffect.Preview
    "stop_process" -> AgentToolEffect.PersistentProcess
    else -> AgentToolEffect.ReadOnly
}
