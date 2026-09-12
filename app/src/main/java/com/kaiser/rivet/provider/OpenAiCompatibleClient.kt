package com.kaiser.rivet.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Covers OpenAI, OpenRouter, and custom OpenAI-compatible endpoints.
// Optional reasoning fields are limited to presets with documented support;
// a custom endpoint receives the baseline chat-completions shape.
internal class OpenAiCompatibleClient(
    private val config: ProviderConfig,
    private val apiKey: String,
) : ProviderClient {

    override suspend fun listModels(): List<ModelInfo> {
        val request = base(Endpoints.openAiModels(config.baseUrl)).get().build()
        http.quick().await(request).use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.body?.string())
            val text = r.body?.string() ?: throw ProviderError.InvalidResponse("no body")
            val data = parseJsonObject(text)?.get("data")?.arr() ?: throw ProviderError.InvalidResponse("not a JSON object")
            return data.mapNotNull { el ->
                el.obj()?.get("id")?.str()?.let { ModelInfo(it, it) }
            }.sortedBy { it.id.lowercase() }
        }
    }

    override suspend fun testConnection(): TestResult {
        try {
            val models = listModels()
            return TestResult(true, "Reachable and authenticated. ${models.size} models listed.")
        } catch (e: ProviderError) {
            return when (e) {
                is ProviderError.UnsupportedEndpoint -> TestResult(
                    false,
                    "Provider reachable, but does not expose a model list. Set the model manually.",
                )
                else -> TestResult(false, e.text())
            }
        }
    }

    override suspend fun streamChat(request: ChatRequest, onDelta: (String) -> Unit): String {
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("messages", buildJsonArray {
                if (request.system.isNotEmpty()) add(buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                })
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.wireName)
                        put("content", m.text)
                    })
                }
            })
            reasoningEffort(config.type, request.model, request.reasoning)?.let {
                put("reasoning_effort", it)
            }
        }
        val httpRequest = base(Endpoints.openAiChat(config.baseUrl))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val out = StringBuilder()
        http.sse(httpRequest) { payload ->
            openAiDelta(payload)?.let {
                if (it.isNotEmpty()) {
                    out.append(it)
                    onDelta(it)
                }
            }
        }
        return out.toString()
    }

    private fun base(url: String): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

// Tolerant extraction of the assistant delta from one streaming chunk;
// unknown shapes are skipped, never fatal.
internal fun openAiDelta(payload: String): String? = try {
    Json.parseToJsonElement(payload).jsonObject["choices"]
        ?.arr()?.firstOrNull()
        ?.obj()?.get("delta")
        ?.obj()?.get("content")
        ?.str()
} catch (e: Exception) {
    null
}

private fun reasoningEffort(
    type: ProviderType,
    model: String,
    level: ReasoningLevel,
): String? {
    if (level == ReasoningLevel.Default) return null
    if (type == ProviderType.OpenRouter) return level.wireEffort
    if (type == ProviderType.OpenAi && openAiSupportsReasoning(model)) {
        return if (level == ReasoningLevel.Max) null else level.wireEffort
    }
    return null
}

private val ReasoningLevel.wireEffort: String
    get() = when (this) {
        ReasoningLevel.Low -> "low"
        ReasoningLevel.Medium -> "medium"
        ReasoningLevel.High -> "high"
        ReasoningLevel.Max -> "xhigh"
        ReasoningLevel.Default -> "high"
    }
