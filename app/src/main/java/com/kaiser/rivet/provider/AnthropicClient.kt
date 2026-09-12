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

// Anthropic Messages API in its native shape. Reasoning is selected by
// documented model family: manual budgets on older models, adaptive thinking
// plus output_config.effort on current models, and no optional fields when
// capability is unknown. Thinking deltas are not rendered.
internal class AnthropicClient(
    private val config: ProviderConfig,
    private val apiKey: String,
) : ProviderClient {

    override suspend fun listModels(): List<ModelInfo> {
        val request = base(Endpoints.anthropicModels(config.baseUrl)).get().build()
        http.quick().await(request).use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.body?.string())
            val text = r.body?.string() ?: throw ProviderError.InvalidResponse("no body")
            val data = parseJsonObject(text)?.get("data")?.arr() ?: throw ProviderError.InvalidResponse("not a JSON object")
            return data.mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                val id = o["id"]?.str() ?: return@mapNotNull null
                ModelInfo(id, o["display_name"]?.str() ?: id)
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
                    "Provider reachable, but model listing is not available. Set the model manually.",
                )
                else -> TestResult(false, e.text())
            }
        }
    }

    override suspend fun streamChat(request: ChatRequest, onDelta: (String) -> Unit): String {
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            val thinkingMode = if (request.reasoning == ReasoningLevel.Default) {
                AnthropicThinkingMode.Unsupported
            } else {
                anthropicThinkingMode(request.model)
            }
            val budget = if (thinkingMode == AnthropicThinkingMode.Manual) {
                request.reasoning.anthropicBudget
            } else {
                0
            }
            put("max_tokens", budget + 8192)
            if (request.system.isNotEmpty()) put("system", request.system)
            when (thinkingMode) {
                AnthropicThinkingMode.Manual -> put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                })
                AnthropicThinkingMode.Adaptive -> {
                    put("thinking", buildJsonObject { put("type", "adaptive") })
                    put("output_config", buildJsonObject {
                        put("effort", request.reasoning.anthropicEffort)
                    })
                }
                AnthropicThinkingMode.Unsupported -> Unit
            }
            put("messages", buildJsonArray {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.wireName)
                        put("content", m.text)
                    })
                }
            })
        }
        val httpRequest = base(Endpoints.anthropicChat(config.baseUrl))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val out = StringBuilder()
        http.sse(httpRequest) { payload ->
            anthropicDelta(payload)?.let {
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
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

internal fun anthropicDelta(payload: String): String? {
    return try {
        val obj = Json.parseToJsonElement(payload).jsonObject
        if (obj["type"]?.str() != "content_block_delta") return null
        if (obj["delta"]?.obj()?.get("type")?.str() != "text_delta") return null
        obj["delta"]?.obj()?.get("text")?.str()
    } catch (e: Exception) {
        null
    }
}

private val ReasoningLevel.anthropicEffort: String
    get() = when (this) {
        ReasoningLevel.Low -> "low"
        ReasoningLevel.Medium -> "medium"
        ReasoningLevel.High, ReasoningLevel.Max -> "high"
        ReasoningLevel.Default -> "high"
    }

private val ReasoningLevel.anthropicBudget: Int
    get() = when (this) {
        ReasoningLevel.Low -> 2048
        ReasoningLevel.Medium -> 8192
        ReasoningLevel.High, ReasoningLevel.Max -> 32768
        ReasoningLevel.Default -> 8192
    }
