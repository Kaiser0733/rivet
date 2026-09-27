package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentToolDefinition

data class ModelInfo(val id: String, val label: String, val inputLimitTokens: Int? = null)

data class TestResult(val ok: Boolean, val message: String)

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

    suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse
}

fun providerClient(config: ProviderConfig, apiKey: String): ProviderClient =
    when (config.type) {
        ProviderType.OpenAiCompatible, ProviderType.OpenAi, ProviderType.OpenRouter ->
            OpenAiCompatibleClient(config, apiKey)
        ProviderType.Anthropic -> AnthropicClient(config, apiKey)
        ProviderType.Gemini -> GeminiClient(config, apiKey)
    }
