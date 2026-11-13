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
    @SerialName("chatgpt_plan") ChatGptPlan("https://api.openai.com/v1"),
    /** An installed GGUF run by Rivet's llama.cpp JNI layer. No API key exists. */
    @SerialName("local") Local("local://"),
}

@Serializable
enum class ReasoningLevel(val wireName: String) {
    Default("off"),
    Low("low"),
    Medium("medium"),
    High("high"),
    XHigh("xhigh"),
    Max("max"),
    Ultra("ultra"),
}

val ALL_REASONING_LEVELS: List<ReasoningLevel> = listOf(
    ReasoningLevel.Default,
    ReasoningLevel.Low,
    ReasoningLevel.Medium,
    ReasoningLevel.High,
    ReasoningLevel.XHigh,
    ReasoningLevel.Max,
    ReasoningLevel.Ultra,
)

fun recommendedFallbackReasoning(failed: ReasoningLevel): ReasoningLevel? = when (failed) {
    ReasoningLevel.Ultra -> ReasoningLevel.Max
    ReasoningLevel.XHigh -> ReasoningLevel.Max
    ReasoningLevel.Max -> ReasoningLevel.High
    ReasoningLevel.High -> ReasoningLevel.Medium
    ReasoningLevel.Medium -> ReasoningLevel.Low
    ReasoningLevel.Low -> ReasoningLevel.Default
    ReasoningLevel.Default -> null
}

const val DEFAULT_CONTEXT_WINDOW_TOKENS = 262_144

@Serializable
data class ProviderHeader(val name: String, val value: String)

@Serializable
data class ModelContextLimit(val model: String, val baseUrl: String, val inputLimitTokens: Int,
                             val maxOutputTokens: Int? = null)

@Serializable
data class ModelTokenSettings(
    val manualContextWindowTokens: Int? = null,
    val manualMaxOutputTokens: Int? = null,
)

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
    val manualContextWindowTokens: Int? = null,
    val manualMaxOutputTokens: Int? = null,
    val modelTokenSettings: Map<String, ModelTokenSettings> = emptyMap(),
)

fun parseTokenCount(raw: String): Int? {
    val cleaned = raw.trim().replace(",", "").replace("_", "").replace(" ", "")
    if (cleaned.isEmpty()) return null
    val suffix = cleaned.last().lowercaseChar()
    val multiplier = when (suffix) {
        'k' -> 1024L
        'm' -> 1024L * 1024L
        else -> 1L
    }
    val numberPart = if (multiplier > 1L) cleaned.dropLast(1) else cleaned
    if (numberPart.isEmpty()) return null
    val tokens = if ('.' in numberPart && multiplier > 1L) {
        val d = numberPart.toDoubleOrNull() ?: return null
        if (!d.isFinite() || d <= 0.0) return null
        (d * multiplier).toLong()
    } else {
        val n = numberPart.toLongOrNull() ?: return null
        if (n <= 0L || n > Int.MAX_VALUE / multiplier) return null
        n * multiplier
    }
    return tokens.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

fun ProviderConfig.manualContextWindowForModel(targetModel: String = model): Int? =
    (modelTokenSettings[targetModel]?.manualContextWindowTokens
        ?: manualContextWindowTokens?.takeIf { targetModel == model })
        ?.takeIf { it > 0 }

fun ProviderConfig.detectedContextWindowForModel(targetModel: String = model): Int? =
    modelContextLimit?.takeIf {
        it.model == targetModel && it.baseUrl == baseUrl && it.inputLimitTokens > 0
    }?.inputLimitTokens

fun ProviderConfig.trustedInputLimitTokens(targetModel: String = model): Int? =
    manualContextWindowForModel(targetModel) ?: detectedContextWindowForModel(targetModel)

fun ProviderConfig.resolvedContextWindowTokens(targetModel: String = model): Int =
    manualContextWindowForModel(targetModel)
        ?: detectedContextWindowForModel(targetModel)
        ?: DEFAULT_CONTEXT_WINDOW_TOKENS

fun ProviderConfig.resolvedManualMaxOutputTokens(targetModel: String = model): Int? =
    (modelTokenSettings[targetModel]?.manualMaxOutputTokens
        ?: manualMaxOutputTokens?.takeIf { targetModel == model })
        ?.takeIf { it > 0 }

fun ProviderConfig.withManualContextWindow(tokens: Int?): ProviderConfig {
    val normalized = tokens?.takeIf { it > 0 }
    val nextMap = if (model.isBlank()) {
        modelTokenSettings
    } else {
        val current = modelTokenSettings[model] ?: ModelTokenSettings()
        val updated = current.copy(manualContextWindowTokens = normalized)
        if (updated.manualContextWindowTokens == null && updated.manualMaxOutputTokens == null) {
            modelTokenSettings - model
        } else {
            modelTokenSettings + (model to updated)
        }
    }
    return copy(
        manualContextWindowTokens = normalized,
        modelTokenSettings = nextMap,
    )
}

fun ProviderConfig.withManualMaxOutputTokens(tokens: Int?): ProviderConfig {
    val normalized = tokens?.takeIf { it > 0 }
    val nextMap = if (model.isBlank()) {
        modelTokenSettings
    } else {
        val current = modelTokenSettings[model] ?: ModelTokenSettings()
        val updated = current.copy(manualMaxOutputTokens = normalized)
        if (updated.manualContextWindowTokens == null && updated.manualMaxOutputTokens == null) {
            modelTokenSettings - model
        } else {
            modelTokenSettings + (model to updated)
        }
    }
    return copy(
        manualMaxOutputTokens = normalized,
        modelTokenSettings = nextMap,
    )
}

internal fun ProviderConfig.openRouterOutputCeiling(targetModel: String = model): Int? {
    if (type != ProviderType.OpenRouter) return null
    val known = trustedInputLimitTokens(targetModel) ?: return null
    val planned = minOf(known, maxOf(2048, minOf(12_000, known / 3)))
    val cap = modelContextLimit?.takeIf { it.model == targetModel && it.baseUrl == baseUrl }
        ?.maxOutputTokens?.takeIf { it > 0 }
    return minOf(planned, cap ?: planned)
}

internal fun ProviderConfig.trustedAnthropicModelMetadata(): AnthropicModelMetadata? = anthropicModelMetadata?.takeIf {
    type == ProviderType.Anthropic && it.model == model && it.baseUrl == baseUrl
}

fun ProviderConfig.selectListedModel(info: ModelInfo): ProviderConfig {
    val savedManual = modelTokenSettings[info.id]
    val carryFromBlank = model.isBlank() && info.id.isNotBlank()
    val manualContext = savedManual?.manualContextWindowTokens
        ?: manualContextWindowTokens?.takeIf { carryFromBlank || info.id == model }
    val manualMaxOutput = savedManual?.manualMaxOutputTokens
        ?: manualMaxOutputTokens?.takeIf { carryFromBlank || info.id == model }
    val updatedSettings = if (savedManual == null && carryFromBlank &&
        (manualContext != null || manualMaxOutput != null)) {
        modelTokenSettings + (info.id to ModelTokenSettings(manualContext, manualMaxOutput))
    } else modelTokenSettings
    val detectedOutput = info.outputLimitTokens?.takeIf { it > 0 }
        ?: info.anthropicMetadata?.maxOutputTokens?.takeIf { it > 0 }
    return copy(
        model = info.id,
        modelContextLimit = info.inputLimitTokens?.takeIf { it > 0 }?.let {
            ModelContextLimit(info.id, baseUrl, it, detectedOutput)
        },
        anthropicModelMetadata = info.anthropicMetadata?.takeIf {
            type == ProviderType.Anthropic && it.model == info.id && it.baseUrl == baseUrl
        },
        manualContextWindowTokens = manualContext,
        manualMaxOutputTokens = manualMaxOutput,
        modelTokenSettings = updatedSettings,
    )
}

fun ProviderConfig.withModelId(newModel: String, listedModels: List<ModelInfo> = emptyList()): ProviderConfig {
    val matched = listedModels.firstOrNull { it.id == newModel }
    if (matched != null) return selectListedModel(matched)
    val savedManual = modelTokenSettings[newModel]
    val carryFromBlank = model.isBlank() && newModel.isNotBlank()
    val manualContext = savedManual?.manualContextWindowTokens
        ?: manualContextWindowTokens?.takeIf { carryFromBlank }
    val manualMaxOutput = savedManual?.manualMaxOutputTokens
        ?: manualMaxOutputTokens?.takeIf { carryFromBlank }
    val updatedSettings = if (savedManual == null && carryFromBlank &&
        (manualContext != null || manualMaxOutput != null)) {
        modelTokenSettings + (newModel to ModelTokenSettings(manualContext, manualMaxOutput))
    } else modelTokenSettings
    return copy(
        model = newModel,
        modelContextLimit = modelContextLimit?.takeIf { it.model == newModel && it.baseUrl == baseUrl },
        anthropicModelMetadata = anthropicModelMetadata?.takeIf { it.model == newModel && it.baseUrl == baseUrl },
        manualContextWindowTokens = manualContext,
        manualMaxOutputTokens = manualMaxOutput,
        modelTokenSettings = updatedSettings,
    )
}

internal enum class AnthropicThinkingMode { Unsupported, Manual, Adaptive }

// Every provider offers the full ladder; the request carries the level in the form
// that provider accepts. The two exceptions are models known not to think at all:
// offering them an effort can only produce the rejection this ladder exists to avoid.
fun offeredReasoning(type: ProviderType, model: String): List<ReasoningLevel> = when (type) {
    // Gemini's transport emits no reasoning configuration, so a level here would be
    // a control that changes nothing when it is chosen.
    ProviderType.Gemini -> listOf(ReasoningLevel.Default)
    ProviderType.Anthropic ->
        if (anthropicThinkingMode(model) == AnthropicThinkingMode.Unsupported)
            listOf(ReasoningLevel.Default) else ALL_REASONING_LEVELS
    ProviderType.OpenAi ->
        if (openAiSupportsReasoning(model)) ALL_REASONING_LEVELS else listOf(ReasoningLevel.Default)
    else -> ALL_REASONING_LEVELS
}

internal fun offeredReasoning(config: ProviderConfig): List<ReasoningLevel> =
    if (config.type == ProviderType.Anthropic) {
        when (anthropicThinkingMode(config)) {
            AnthropicThinkingMode.Manual -> if (
                anthropicThinkingBudget(config.copy(reasoning = ReasoningLevel.Low)) >= 1024
            ) ALL_REASONING_LEVELS else listOf(ReasoningLevel.Default)
            AnthropicThinkingMode.Adaptive -> ALL_REASONING_LEVELS
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
    val normalizedBase = config.baseUrl.trim().trimEnd('/').removeSuffix("/v1")
    return if (normalizedBase == ProviderType.Anthropic.defaultBaseUrl) {
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
