package com.kaiser.rivet.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderType(val defaultBaseUrl: String) {
    @SerialName("openai_compatible") OpenAiCompatible(""),
    @SerialName("openai") OpenAi("https://api.openai.com/v1"),
    @SerialName("anthropic") Anthropic("https://api.anthropic.com"),
    @SerialName("gemini") Gemini("https://generativelanguage.googleapis.com"),
    @SerialName("openrouter") OpenRouter("https://openrouter.ai/api/v1"),
}

@Serializable
enum class ReasoningLevel { Default, Low, Medium, High, Max }

@Serializable
data class ProviderHeader(val name: String, val value: String)

// API keys never live in this structure; they go to SecretStore only.
@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val model: String,
    val reasoning: ReasoningLevel = ReasoningLevel.Default,
    val headers: List<ProviderHeader> = emptyList(),
)

private val standardReasoning = listOf(
    ReasoningLevel.Default,
    ReasoningLevel.Low,
    ReasoningLevel.Medium,
    ReasoningLevel.High,
)

internal enum class AnthropicThinkingMode { Unsupported, Manual, Adaptive }

fun offeredReasoning(type: ProviderType, model: String): List<ReasoningLevel> = when (type) {
    ProviderType.OpenAiCompatible, ProviderType.Gemini -> listOf(ReasoningLevel.Default)
    ProviderType.OpenRouter -> ReasoningLevel.entries
    ProviderType.OpenAi -> if (openAiSupportsReasoning(model)) standardReasoning else listOf(ReasoningLevel.Default)
    ProviderType.Anthropic -> when (anthropicThinkingMode(model)) {
        AnthropicThinkingMode.Manual, AnthropicThinkingMode.Adaptive -> standardReasoning
        AnthropicThinkingMode.Unsupported -> listOf(ReasoningLevel.Default)
    }
}

internal fun openAiSupportsReasoning(model: String): Boolean {
    val id = model.lowercase().substringAfterLast('/')
    return id == "o1" || id.startsWith("o1-") ||
        id == "o3" || id.startsWith("o3-") ||
        id == "o4" || id.startsWith("o4-") ||
        id == "gpt-5" || id.startsWith("gpt-5-") || id.startsWith("gpt-5.")
}

internal fun anthropicThinkingMode(model: String): AnthropicThinkingMode {
    val id = model.lowercase().substringAfterLast('/')
    if (Regex("^claude-(opus|sonnet|fable|mythos)-5(?:-|$)").containsMatchIn(id)) {
        return AnthropicThinkingMode.Adaptive
    }
    val fourthGeneration = Regex("^claude-(opus|sonnet)-4-(\\d+)(?:-|$)").find(id)
    val minor = fourthGeneration?.groupValues?.get(2)
    if (minor != null && minor.length <= 2 && minor.toInt() >= 6) {
        return AnthropicThinkingMode.Adaptive
    }
    if (id.startsWith("claude-3-7-sonnet-") || id == "claude-3-7-sonnet") {
        return AnthropicThinkingMode.Manual
    }
    if (Regex("^claude-(opus|sonnet)-4-(?:[1-5])(?:-|$)").containsMatchIn(id) ||
        Regex("^claude-(opus|sonnet)-4-\\d{8}$").containsMatchIn(id) ||
        Regex("^claude-haiku-4-5(?:-|$)").containsMatchIn(id)
    ) {
        return AnthropicThinkingMode.Manual
    }
    return AnthropicThinkingMode.Unsupported
}

// Free-form headers may never override a transport's auth headers; a stray
// Authorization here could shadow or resend the stored key.
fun List<ProviderHeader>.sanitized(): List<ProviderHeader> = filterNot {
    val n = it.name.trim().lowercase()
    n == "authorization" || n == "x-api-key" || n == "x-goog-api-key"
}

fun parseHeaders(text: String): List<ProviderHeader> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null
            else ProviderHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
        .toList()
