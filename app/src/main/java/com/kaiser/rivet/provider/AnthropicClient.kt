package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
            if (!r.isSuccessful) throw httpError(r.code, r.errorText())
            val text = r.readBoundedBody() ?: throw ProviderError.InvalidResponse("no body")
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
                    add(anthropicMessage(m))
                }
            })
            if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
                request.tools.forEach { tool -> add(buildJsonObject {
                    put("name", tool.name); put("description", tool.description)
                    put("input_schema", tool.parameters)
                }) }
            })
        }
        val httpRequest = base(Endpoints.anthropicChat(config.baseUrl))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val stream = AnthropicAgentStream(onDelta)
        http.sse(httpRequest) { payload ->
            stream.accept(payload)
        }
        return stream.response()
    }

    private fun base(url: String): Request.Builder {
        val b = requestBuilder(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

private fun anthropicMessage(message: AgentMessage): JsonObject = when (message.role) {
    AgentRole.User -> buildJsonObject { put("role", "user"); put("content", message.text) }
    AgentRole.Assistant -> buildJsonObject {
        put("role", "assistant")
        put("content", buildJsonArray {
            message.transportState?.let { raw ->
                val state = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
                if (state?.get("provider")?.str() == "anthropic") {
                    state["blocks"]?.arr()?.forEach(::add)
                }
            }
            if (message.text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", message.text) })
            message.toolCalls.forEach { call ->
                val input = try { Json.parseToJsonElement(call.arguments) as? JsonObject } catch (_: Exception) { null }
                    ?: throw ProviderError.InvalidResponse("invalid tool input")
                add(buildJsonObject {
                    put("type", "tool_use"); put("id", call.id); put("name", call.name); put("input", input)
                })
            }
        })
    }
    AgentRole.Tool -> buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray { message.toolResults.forEach { result -> add(buildJsonObject {
            put("type", "tool_result"); put("tool_use_id", result.callId); put("content", result.content)
            if (result.error) put("is_error", true)
        }) } })
    }
}

private class AnthropicAgentStream(private val onDelta: (String) -> Unit) {
    private data class Tool(
        val id: String,
        val name: String,
        val initial: JsonObject,
        val fragments: StringBuilder = StringBuilder(),
    )
    private data class Thinking(
        val type: String,
        val thinking: StringBuilder = StringBuilder(),
        var signature: String? = null,
        var data: String? = null,
    )
    private val text = StringBuilder()
    private val tools = sortedMapOf<Int, Tool>()
    private val thinking = sortedMapOf<Int, Thinking>()
    private var usage: com.kaiser.rivet.agent.AgentUsage? = null
    private var messageStopped = false
    private var stopReason: String? = null

    fun accept(payload: String) {
        val root = parseJsonObject(payload) ?: throw ProviderError.InvalidResponse("invalid stream event")
        when (root["type"]?.str()) {
            "message_stop" -> messageStopped = true
            "message_start" -> anthropicUsage(root["message"]?.obj() ?: root)?.let { usage = it }
            "message_delta" -> {
                root["delta"]?.obj()?.get("stop_reason")?.str()?.let { stopReason = it }
                anthropicUsage(root)?.let { delta ->
                    val prior = usage
                    usage = delta.copy(inputTokens = delta.inputTokens ?: prior?.inputTokens,
                        cacheReadTokens = delta.cacheReadTokens ?: prior?.cacheReadTokens)
                }
            }
            "error" -> {
                val message = root["error"]?.obj()?.get("message")?.str()
                    ?: throw ProviderError.InvalidResponse("stream error")
                throw providerMessage(message, root["error"]?.obj()?.get("type")?.str())
            }
            "content_block_start" -> {
                val index = root["index"]?.jsonPrimitive?.intOrNull
                    ?: throw ProviderError.InvalidResponse("content index missing")
                val block = root["content_block"]?.obj()
                    ?: throw ProviderError.InvalidResponse("content block missing")
                when (val type = block["type"]?.str()) {
                    "tool_use" -> tools[index] = Tool(
                        block["id"]?.str() ?: throw ProviderError.InvalidResponse("tool id missing"),
                        block["name"]?.str() ?: throw ProviderError.InvalidResponse("tool name missing"),
                        block["input"]?.obj() ?: buildJsonObject {},
                    )
                    "thinking", "redacted_thinking" -> thinking[index] = Thinking(
                        type,
                        thinking = StringBuilder(block["thinking"]?.str().orEmpty()),
                        signature = block["signature"]?.str(),
                        data = block["data"]?.str(),
                    )
                }
            }
            "content_block_delta" -> {
                val index = root["index"]?.jsonPrimitive?.intOrNull
                    ?: throw ProviderError.InvalidResponse("content index missing")
                val delta = root["delta"]?.obj() ?: throw ProviderError.InvalidResponse("delta missing")
                when (delta["type"]?.str()) {
                    "text_delta" -> delta["text"]?.str()?.let { value -> text.append(value); onDelta(value) }
                    "input_json_delta" -> delta["partial_json"]?.str()?.let { tools[index]?.fragments?.append(it) }
                    "thinking_delta" -> delta["thinking"]?.str()?.let { thinking[index]?.thinking?.append(it) }
                    "signature_delta" -> thinking[index]?.signature = delta["signature"]?.str()
                }
            }
        }
    }

    fun response(): AgentResponse {
        if (!messageStopped) throw ProviderError.InvalidResponse("incomplete stream")
        val reason = stopReason
        if (tools.isNotEmpty() && reason != "tool_use" ||
            tools.isEmpty() && reason !in setOf("end_turn", "stop_sequence", "refusal")) {
            throw ProviderError.IncompleteGeneration(reason ?: "missing stop reason")
        }
        val calls = tools.values.map { tool ->
            val arguments = tool.fragments.toString().ifEmpty { tool.initial.toString() }
            try {
                if (Json.parseToJsonElement(arguments) !is JsonObject) throw IllegalArgumentException()
            } catch (_: Exception) {
                throw ProviderError.InvalidResponse("invalid tool input")
            }
            AgentToolCall(tool.id, tool.name, arguments)
        }
        val blocks = thinking.values.map { block -> buildJsonObject {
            put("type", block.type)
            if (block.type == "thinking") {
                put("thinking", block.thinking.toString())
                block.signature?.let { put("signature", it) }
            } else block.data?.let { put("data", it) }
        } }
        val state = blocks.takeIf { it.isNotEmpty() }?.let { values -> buildJsonObject {
            put("provider", "anthropic")
            put("blocks", JsonArray(values))
        }.toString() }
        return AgentResponse(text.toString(), calls, state, usage)
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
