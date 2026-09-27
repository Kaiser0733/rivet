package com.kaiser.rivet.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AgentRole {
    @SerialName("user") User,
    @SerialName("assistant") Assistant,
    @SerialName("tool") Tool,
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

class PreparedAgentTool(
    val call: AgentToolCall,
    val approval: AgentApprovalRequest?,
    val resultContentLimitBytes: Int = AgentLoop.MAX_TOOL_RESULT_BYTES,
    val execute: suspend () -> AgentToolResult,
)
