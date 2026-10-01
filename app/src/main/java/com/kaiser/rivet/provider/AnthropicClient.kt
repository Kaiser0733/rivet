package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.modelMessages
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Anthropic Messages API in its native shape. Selected model metadata controls
// manual versus adaptive reasoning; legacy official-provider configs retain
// their documented model-family defaults. Thinking deltas are not rendered.
internal class AnthropicClient(
    private val config: ProviderConfig,
    private val apiKey: String,
) : ProviderClient {

    override suspend fun listModels(): List<ModelInfo> {
        val root = requestBuilder(Endpoints.anthropicModels(config.baseUrl)).build().url
        val models = linkedMapOf<String, ModelInfo>()
        var afterId: String? = null
        repeat(10) {
            val url = root.newBuilder().addQueryParameter("limit", "1000").apply {
                afterId?.let { addQueryParameter("after_id", it) }
            }.build()
            val request = base(url.toString()).get().build()
            http.quick().await(request).use { r ->
                if (!r.isSuccessful) throw httpError(r.code, r.errorText())
                val text = r.readBoundedBody() ?: throw ProviderError.InvalidResponse("no body")
                val page = parseJsonObject(text) ?: throw ProviderError.InvalidResponse("not a JSON object")
                val data = page["data"]?.arr() ?: throw ProviderError.InvalidResponse("missing model data")
                data.forEach { element ->
                    val model = element.obj() ?: return@forEach
                    val id = model["id"]?.str() ?: return@forEach
                    val thinking = model["capabilities"]?.obj()?.get("thinking")?.obj()
                    val types = thinking?.get("types")?.obj()
                    models[id] = ModelInfo(id, model["display_name"]?.str() ?: id,
                        model["max_input_tokens"].positiveInt(), AnthropicModelMetadata(
                            model = id,
                            baseUrl = config.baseUrl,
                            maxOutputTokens = model["max_tokens"].positiveInt(),
                            thinkingSupported = thinking?.get("supported").booleanValue(),
                            adaptiveThinkingSupported = types?.get("adaptive")?.obj()
                                ?.get("supported").booleanValue(),
                            manualThinkingSupported = types?.get("enabled")?.obj()
                                ?.get("supported").booleanValue(),
                        ))
                }
                if (page["has_more"]?.jsonPrimitive?.booleanOrNull != true) {
                    return models.values.sortedBy { it.id.lowercase() }
                }
                val next = page["last_id"]?.str()
                    ?: throw ProviderError.InvalidResponse("missing model cursor")
                if (next == afterId) throw ProviderError.InvalidResponse("repeated model cursor")
                afterId = next
            }
        }
        throw ProviderError.InvalidResponse("model listing exceeds page limit")
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

    override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse {
        val body = requestBody(request)
        val httpRequest = base(Endpoints.anthropicChat(config.baseUrl))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val stream = AnthropicAgentStream(onDelta)
        http.sse(httpRequest) { payload ->
            stream.accept(payload)
        }
        return stream.response()
    }

    internal fun requestBody(request: AgentRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        // Automatic five-minute caching is documented for the native Claude
        // endpoint. Unknown proxies retain their existing request contract.
        val endpoint = requestBuilder(Endpoints.anthropicChat(config.baseUrl)).build().url
        if (config.type == ProviderType.Anthropic && endpoint.scheme == "https" &&
            endpoint.host == "api.anthropic.com" && endpoint.port == 443 && endpoint.encodedPath == "/v1/messages" &&
            request.model.startsWith("claude-")) {
            put("cache_control", buildJsonObject { put("type", "ephemeral") })
        }
        val modelConfig = if (config.model == request.model) config else config.copy(
            model = request.model,
            anthropicModelMetadata = null,
            modelContextLimit = null,
        )
        val turnConfig = modelConfig.copy(reasoning = request.reasoning)
        val thinkingMode = if (request.reasoning == ReasoningLevel.Default) {
            AnthropicThinkingMode.Unsupported
        } else {
            anthropicThinkingMode(modelConfig)
        }
        val budget = if (thinkingMode == AnthropicThinkingMode.Manual)
            anthropicThinkingBudget(turnConfig) else 0
        if (thinkingMode == AnthropicThinkingMode.Manual && budget < MIN_MANUAL_THINKING_BUDGET) {
            throw ProviderError.UnsupportedConfiguration()
        }
        put("max_tokens", anthropicOutputCeiling(turnConfig))
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
            modelMessages(request.messages).forEach { m ->
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

    private fun base(url: String): Request.Builder {
        val b = requestBuilder(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

private fun anthropicMessage(message: AgentMessage): JsonObject = when (message.role) {
    AgentRole.Context -> throw ProviderError.InvalidResponse("unprojected internal context")
    AgentRole.User -> buildJsonObject { put("role", "user"); put("content", message.text) }
    AgentRole.Assistant -> buildJsonObject {
        put("role", "assistant")
        put("content", buildJsonArray {
            message.transportState?.let { raw ->
                val state = parseJsonObject(raw)
                if (state?.get("provider")?.str() == "anthropic") {
                    state["blocks"]?.arr()?.forEach(::add)
                }
            }
            if (message.text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", message.text) })
            message.toolCalls.forEach { call ->
                val input = parseJsonObject(call.arguments)
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
                        cacheReadTokens = delta.cacheReadTokens ?: prior?.cacheReadTokens,
                        cacheCreationTokens = delta.cacheCreationTokens ?: prior?.cacheCreationTokens)
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
            if (!jsonNestingWithinLimit(arguments, 32) || parseJsonObject(arguments) == null) {
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

internal fun anthropicOutputCeiling(config: ProviderConfig): Int {
    val mode = anthropicThinkingMode(config)
    val legacyCeiling = when {
        mode == AnthropicThinkingMode.Adaptive ->
            config.trustedAnthropicModelMetadata()?.maxOutputTokens ?: DEFAULT_OUTPUT_TOKENS
        mode == AnthropicThinkingMode.Manual && config.reasoning != ReasoningLevel.Default ->
            DEFAULT_OUTPUT_TOKENS + config.reasoning.anthropicBudget
        else -> DEFAULT_OUTPUT_TOKENS
    }
    val outputLimit = config.trustedAnthropicModelMetadata()?.maxOutputTokens
    val inputWindowOutputCap = config.trustedInputLimitTokens()?.let { maxOf(1, it / 4) }
    val actualMaximum = listOfNotNull(outputLimit, inputWindowOutputCap).minOrNull()
    return actualMaximum?.let { minOf(legacyCeiling, it) } ?: legacyCeiling
}

// Manual budgets leave the existing 8K response allowance when possible and
// never exceed Anthropic's max_tokens ceiling or its minimum 1K budget rule.
internal fun anthropicThinkingBudget(config: ProviderConfig): Int {
    if (config.reasoning == ReasoningLevel.Default ||
        anthropicThinkingMode(config) != AnthropicThinkingMode.Manual) return 0
    val outputCeiling = anthropicOutputCeiling(config)
    if (outputCeiling < MIN_MANUAL_THINKING_BUDGET * 2) return 0
    return minOf(
        config.reasoning.anthropicBudget,
        maxOf(MIN_MANUAL_THINKING_BUDGET, outputCeiling - DEFAULT_OUTPUT_TOKENS),
    )
}

// Retain the 0.9.0 name-table helper for callers that only have legacy model data.
internal fun anthropicOutputCeiling(model: String, reasoning: ReasoningLevel): Int =
    DEFAULT_OUTPUT_TOKENS + if (reasoning != ReasoningLevel.Default &&
        anthropicThinkingMode(model) == AnthropicThinkingMode.Manual) reasoning.anthropicBudget else 0

private val ReasoningLevel.anthropicBudget: Int
    get() = when (this) {
        ReasoningLevel.Low -> 2048
        ReasoningLevel.Medium -> 8192
        ReasoningLevel.High, ReasoningLevel.Max -> 32768
        ReasoningLevel.Default -> 8192
    }

private fun JsonElement?.booleanValue(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private const val DEFAULT_OUTPUT_TOKENS = 8192
private const val MIN_MANUAL_THINKING_BUDGET = 1024
