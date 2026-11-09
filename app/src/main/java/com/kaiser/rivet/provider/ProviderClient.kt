package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.local.LocalProviderClient
import com.kaiser.rivet.local.LocalRuntime

data class ModelInfo(
    val id: String,
    val label: String,
    val inputLimitTokens: Int? = null,
    val anthropicMetadata: AnthropicModelMetadata? = null,
    val outputLimitTokens: Int? = null,
)

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

    suspend fun streamAgent(
        request: AgentRequest,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit,
    ): AgentResponse
}

fun providerClient(config: ProviderConfig, apiKey: String): ProviderClient =
    when (config.type) {
        ProviderType.OpenAiCompatible, ProviderType.OpenAi, ProviderType.OpenRouter ->
            OpenAiCompatibleClient(config, apiKey)
        ProviderType.Anthropic -> AnthropicClient(config, apiKey)
        ProviderType.Gemini -> GeminiClient(config, apiKey)
        ProviderType.ChatGptPlan -> ChatGptPlanClient(config, apiKey)
        ProviderType.Local -> {
            val manager = LocalRuntime.models()
            if (manager == null) UnavailableLocalClient else
                LocalProviderClient(config, manager, manager.app)
        }
    }

private object UnavailableLocalClient : ProviderClient {
    private fun unavailable(): Nothing =
        throw ProviderError.ProviderMessage("Local models aren't available in this build.")
    override suspend fun listModels(): List<ModelInfo> = unavailable()
    override suspend fun testConnection(): TestResult = unavailable()
    override suspend fun streamAgent(
        request: AgentRequest,
        onReasoning: (String) -> Unit,
        onDelta: (String) -> Unit,
    ): AgentResponse = unavailable()
}
