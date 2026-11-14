package com.kaiser.rivet.storage

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel

/** Durable session context operations shared across coordinator and worker stores. */
interface ContextStore {
    suspend fun compact(
        expected: List<AgentMessage>,
        retained: List<AgentMessage>,
        summary: String,
        attempt: ContextAttempt? = null,
        expectedSessionId: String? = null,
    ): List<AgentMessage>

    suspend fun recordContextFailure(sessionId: String, attempt: ContextAttempt)

    suspend fun recordUsage(
        sessionId: String,
        turnId: String,
        providerId: String,
        model: String,
        usage: AgentUsage?,
        requestMessages: List<AgentMessage> = emptyList(),
        system: String = "",
        tools: List<AgentToolDefinition> = emptyList(),
        endpoint: String = "",
        providerType: ProviderType? = null,
        reasoning: ReasoningLevel = ReasoningLevel.Default,
    )

    suspend fun contextEstimate(
        sessionId: String,
        providerId: String,
        model: String,
        messages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition> = emptyList(),
        endpoint: String = "",
        reasoning: ReasoningLevel = ReasoningLevel.Default,
    ): ContextEstimate
}
