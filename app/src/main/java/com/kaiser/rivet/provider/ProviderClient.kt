package com.kaiser.rivet.provider

import com.kaiser.rivet.chat.ChatMessage

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

interface ProviderClient {
    suspend fun listModels(): List<ModelInfo>

    suspend fun testConnection(): TestResult

    // Returns the full accumulated response text; onDelta receives each
    // chunk as it arrives.
    suspend fun streamChat(request: ChatRequest, onDelta: (String) -> Unit): String
}

fun providerClient(config: ProviderConfig, apiKey: String): ProviderClient =
    when (config.type) {
        ProviderType.OpenAiCompatible, ProviderType.OpenAi, ProviderType.OpenRouter ->
            OpenAiCompatibleClient(config, apiKey)
        ProviderType.Anthropic -> AnthropicClient(config, apiKey)
        ProviderType.Gemini -> GeminiClient(config, apiKey)
    }
