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

@Serializable
data class ModelContextLimit(val model: String, val baseUrl: String, val inputLimitTokens: Int)

@Serializable
data class AnthropicModelMetadata(
    val model: String,
    val baseUrl: String,
    val maxOutputTokens: Int? = null,
    val thinkingSupported: Boolean? = null,
    val adaptiveThinkingSupported: Boolean? = null,
    val manualThinkingSupported: Boolean? = null,
)

// API keys never live in this structure. ProviderStore encrypts custom
// header values before persisting this otherwise provider-neutral config.
@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val model: String,
    val reasoning: ReasoningLevel = ReasoningLevel.Default,
    val headers: List<ProviderHeader> = emptyList(),
    val modelContextLimit: ModelContextLimit? = null,
    val anthropicModelMetadata: AnthropicModelMetadata? = null,
)

fun ProviderConfig.trustedInputLimitTokens(): Int? = modelContextLimit?.takeIf {
    type in setOf(ProviderType.Anthropic, ProviderType.Gemini, ProviderType.OpenRouter) &&
        it.model == model && it.baseUrl == baseUrl && it.inputLimitTokens > 0
}?.inputLimitTokens

internal fun ProviderConfig.trustedAnthropicModelMetadata(): AnthropicModelMetadata? = anthropicModelMetadata?.takeIf {
    type == ProviderType.Anthropic && it.model == model && it.baseUrl == baseUrl
}

fun ProviderConfig.selectListedModel(info: ModelInfo): ProviderConfig = copy(
    model = info.id,
    modelContextLimit = info.inputLimitTokens?.takeIf {
        it > 0 && type in setOf(ProviderType.Anthropic, ProviderType.Gemini, ProviderType.OpenRouter)
    }?.let { ModelContextLimit(info.id, baseUrl, it) },
    anthropicModelMetadata = info.anthropicMetadata?.takeIf {
        type == ProviderType.Anthropic && it.model == info.id && it.baseUrl == baseUrl
    },
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

internal fun offeredReasoning(config: ProviderConfig): List<ReasoningLevel> =
    if (config.type == ProviderType.Anthropic) {
        when (anthropicThinkingMode(config)) {
            AnthropicThinkingMode.Manual -> if (
                anthropicThinkingBudget(config.copy(reasoning = ReasoningLevel.Low)) >= 1024
            ) standardReasoning else listOf(ReasoningLevel.Default)
            AnthropicThinkingMode.Adaptive -> standardReasoning
            AnthropicThinkingMode.Unsupported -> listOf(ReasoningLevel.Default)
        }
    } else offeredReasoning(config.type, config.model)

internal fun openAiSupportsReasoning(model: String): Boolean {
    val id = model.lowercase().substringAfterLast('/')
    return id == "o1" || id.startsWith("o1-") ||
        id == "o3" || id.startsWith("o3-") ||
        id == "o4" || id.startsWith("o4-") ||
        id == "gpt-5" || id.startsWith("gpt-5-") || id.startsWith("gpt-5.")
}

private val anthropicAdaptiveModels = setOf(
    "claude-fable-5-1",
    "claude-mythos-5-1",
    "claude-fable-5",
    "claude-mythos-5",
    "claude-mythos-preview",
    "claude-opus-5",
    "claude-sonnet-5",
    "claude-opus-4-8",
    "claude-opus-4-7",
    "claude-opus-4-6",
    "claude-sonnet-4-6",
)

private val anthropicManualModel =
    Regex("^claude-(?:opus|sonnet|haiku)-4-5(?:-\\d{8})?$")

internal fun anthropicThinkingMode(model: String): AnthropicThinkingMode {
    val id = model.lowercase().substringAfterLast('/')
    return when {
        id in anthropicAdaptiveModels -> AnthropicThinkingMode.Adaptive
        anthropicManualModel.matches(id) -> AnthropicThinkingMode.Manual
        else -> AnthropicThinkingMode.Unsupported
    }
}

internal fun anthropicThinkingMode(config: ProviderConfig): AnthropicThinkingMode {
    val metadata = config.trustedAnthropicModelMetadata()
    val hasCapabilityMetadata = metadata?.let {
        it.thinkingSupported != null || it.adaptiveThinkingSupported != null || it.manualThinkingSupported != null
    } == true
    if (hasCapabilityMetadata) {
        if (metadata?.thinkingSupported == false) return AnthropicThinkingMode.Unsupported
        return when {
            metadata?.adaptiveThinkingSupported == true -> AnthropicThinkingMode.Adaptive
            metadata?.manualThinkingSupported == true -> AnthropicThinkingMode.Manual
            else -> AnthropicThinkingMode.Unsupported
        }
    }
    return if (config.baseUrl.trimEnd('/') == ProviderType.Anthropic.defaultBaseUrl) {
        anthropicThinkingMode(config.model)
    } else {
        AnthropicThinkingMode.Unsupported
    }
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
