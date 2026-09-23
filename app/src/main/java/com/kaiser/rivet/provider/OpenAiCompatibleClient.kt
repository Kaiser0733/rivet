package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
        return streamAgent(
            AgentRequest(
                request.model,
                request.messages.map { message -> AgentMessage(
                    if (message.role.wireName == "assistant") AgentRole.Assistant else AgentRole.User,
                    text = message.text,
                ) },
                request.system,
                request.reasoning,
                emptyList(),
            ),
            onDelta,
        ).text
    }

    override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse {
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            if (config.type == ProviderType.OpenAi || config.type == ProviderType.OpenRouter) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            put("messages", buildJsonArray {
                if (request.system.isNotEmpty()) add(buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                })
                request.messages.forEach { message -> openAiMessages(message).forEach(::add) }
            })
            if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
                request.tools.forEach { tool -> add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.name); put("description", tool.description)
                        put("parameters", tool.parameters)
                    })
                }) }
            })
            reasoningEffort(config.type, request.model, request.reasoning)?.let {
                put("reasoning_effort", it)
            }
        }
        val httpRequest = base(Endpoints.openAiChat(config.baseUrl))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val stream = OpenAiAgentStream(onDelta)
        http.sse(httpRequest) { payload ->
            stream.accept(payload)
        }
        return stream.response()
    }

    private fun base(url: String): Request.Builder {
        val b = requestBuilder(url)
            .header("Authorization", "Bearer $apiKey")
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

private fun openAiMessages(message: AgentMessage): List<JsonObject> = when (message.role) {
    AgentRole.User -> listOf(buildJsonObject { put("role", "user"); put("content", message.text) })
    AgentRole.Assistant -> listOf(buildJsonObject {
        put("role", "assistant"); put("content", message.text)
        if (message.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
            message.toolCalls.forEach { call -> add(buildJsonObject {
                put("id", call.id); put("type", "function")
                put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments) })
            }) }
        })
    })
    AgentRole.Tool -> message.toolResults.map { result -> buildJsonObject {
        put("role", "tool"); put("tool_call_id", result.callId); put("content", result.content)
    } }
}

private class OpenAiAgentStream(private val onDelta: (String) -> Unit) {
    private data class Pending(
        var id: String? = null,
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )
    private val text = StringBuilder()
    private val tools = sortedMapOf<Int, Pending>()
    private var usage: com.kaiser.rivet.agent.AgentUsage? = null

    fun accept(payload: String) {
        val root = parseJsonObject(payload) ?: throw ProviderError.InvalidResponse("invalid stream event")
        root["error"]?.let { error ->
            val message = error.obj()?.get("message")?.str()
                ?: throw ProviderError.InvalidResponse("invalid stream error")
            throw ProviderError.ProviderMessage(message)
        }
        openAiUsage(root)?.let { usage = it }
        val delta = root["choices"]?.arr()?.firstOrNull()?.obj()?.get("delta")?.obj() ?: return
        delta["content"]?.str()?.takeIf { it.isNotEmpty() }?.let { value ->
            text.append(value); onDelta(value)
        }
        delta["tool_calls"]?.arr()?.forEach { element ->
            val value = element.obj() ?: throw ProviderError.InvalidResponse("invalid tool delta")
            val index = value["index"]?.jsonPrimitive?.intOrNull
                ?: throw ProviderError.InvalidResponse("tool index missing")
            val pending = tools.getOrPut(index) { Pending() }
            value["id"]?.str()?.let { pending.id = it }
            value["function"]?.obj()?.let { function ->
                function["name"]?.str()?.let(pending.name::append)
                function["arguments"]?.str()?.let(pending.arguments::append)
            }
        }
    }

    fun response(): AgentResponse = AgentResponse(
        text = text.toString(),
        toolCalls = tools.values.map { pending ->
            AgentToolCall(
                pending.id?.takeIf { it.isNotBlank() }
                    ?: throw ProviderError.InvalidResponse("tool id missing"),
                pending.name.toString().takeIf { it.isNotBlank() }
                    ?: throw ProviderError.InvalidResponse("tool name missing"),
                pending.arguments.toString(),
            )
        },
        usage = usage,
    )
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
