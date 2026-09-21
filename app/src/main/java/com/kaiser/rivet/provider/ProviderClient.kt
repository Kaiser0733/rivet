package com.kaiser.rivet.provider

import com.kaiser.rivet.chat.ChatMessage
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentToolDefinition

data class ModelInfo(val id: String, val label: String)

data class TestResult(val ok: Boolean, val message: String)

// One request shape for all transports; each client maps it onto its wire
// format. `model` is snapshotted by the caller at send time.
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val system: String,
    val reasoning: ReasoningLevel,
)

data class AgentRequest(
    val model: String,
    val messages: List<AgentMessage>,
    val system: String,
    val reasoning: ReasoningLevel,
    val tools: List<AgentToolDefinition>,
)

interface ProviderClient {
    suspend fun listModels(): List<ModelInfo>

    suspend fun testConnection(): TestResult

    // Returns the full accumulated response text; onDelta receives each
    // chunk as it arrives.
    suspend fun streamChat(request: ChatRequest, onDelta: (String) -> Unit): String

    suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse
}

fun providerClient(config: ProviderConfig, apiKey: String): ProviderClient =
    when (config.type) {
        ProviderType.OpenAiCompatible, ProviderType.OpenAi, ProviderType.OpenRouter ->
            OpenAiCompatibleClient(config, apiKey)
        ProviderType.Anthropic -> AnthropicClient(config, apiKey)
        ProviderType.Gemini -> GeminiClient(config, apiKey)
    }
