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

// "Max" maps to OpenRouter's documented xhigh enum; every other transport
// only gets the portable low/medium/high subset, so the option is hidden.
fun offeredReasoning(type: ProviderType): List<ReasoningLevel> =
    if (type == ProviderType.OpenRouter) ReasoningLevel.entries
    else ReasoningLevel.entries.filter { it != ReasoningLevel.Max }

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
