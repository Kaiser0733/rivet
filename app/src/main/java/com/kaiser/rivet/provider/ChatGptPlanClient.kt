package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.modelMessages
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Sign in with ChatGPT (SIWC) inference client. Talks to the Responses API
// (POST /v1/responses), NOT /chat/completions — the wire format, SSE events,
// and model list shape are all different from OpenAiCompatibleClient.
// The access token arrives via the providerClient() factory's String slot;
// no token fields live in ProviderConfig.
internal class ChatGptPlanClient(
    private val config: ProviderConfig,
    private val accessToken: String,
) : ProviderClient {

    override suspend fun listModels(): List<ModelInfo> {
        val request = base(Endpoints.openAiModels(config.baseUrl)).get().build()
        http.quick().await(request).use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.errorText())
            val text = r.readBoundedBody() ?: throw ProviderError.InvalidResponse("no body")
            return parseChatGptModels(text)
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

    override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse {
        val body = buildJsonObject {
            put("model", request.model)
            // Responses API system prompt field; the agent's project
            // instructions live here, so it must not be dropped.
            if (request.system.isNotEmpty()) put("instructions", request.system)
            put("store", false)
            put("stream", true)
            put("input", buildJsonArray {
                modelMessages(request.messages).forEach { message ->
                    responsesInputItems(message).forEach(::add)
                }
            })
            if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
                request.tools.forEach { tool -> add(buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                }) }
            })
            responsesEffort(request.reasoning)?.let { effort ->
                put("reasoning", buildJsonObject { put("effort", effort) })
            }
        }
        val httpRequest = base(Endpoints.openAiResponses(config.baseUrl))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val stream = ResponsesAgentStream(onDelta)
        http.responsesSse(httpRequest) { payload ->
            stream.accept(payload)
        }
        return stream.response()
    }

    private fun base(url: String): Request.Builder {
        val b = requestBuilder(url)
            .header("Authorization", "Bearer $accessToken")
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

// Responses API reasoning shape is a nested object, not the chat-completions
// `reasoning_effort` string. "xhigh" is an OpenRouter-only value, so Max is
// dropped rather than sent. Default omits the key entirely.
private fun responsesEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.Low -> "low"
    ReasoningLevel.Medium -> "medium"
    ReasoningLevel.High -> "high"
    ReasoningLevel.Default, ReasoningLevel.Max -> null
}

// SIWC model list shape: {"models": [{slug, display_name, visibility}]}.
// Only visibility=="list" entries are user-selectable. Pure; unit-testable.
internal fun parseChatGptModels(text: String): List<ModelInfo> {
    val models = parseJsonObject(text)?.get("models")?.arr()
        ?: throw ProviderError.InvalidResponse("not a JSON object")
    return models.mapNotNull { el ->
        val model = el.obj() ?: return@mapNotNull null
        if (model["visibility"]?.str() != "list") return@mapNotNull null
        val slug = model["slug"]?.str() ?: return@mapNotNull null
        ModelInfo(slug, model["display_name"]?.str() ?: slug)
    }.sortedBy { it.id.lowercase() }
}

private fun responsesInputItems(message: AgentMessage): List<JsonObject> = when (message.role) {
    AgentRole.Context -> throw ProviderError.InvalidResponse("unprojected internal context")
    AgentRole.User -> listOf(buildJsonObject { put("role", "user"); put("content", message.text) })
    AgentRole.Assistant -> buildList {
        if (message.text.isNotEmpty()) add(buildJsonObject {
            put("role", "assistant"); put("content", message.text)
        })
        message.toolCalls.forEach { call -> add(buildJsonObject {
            put("type", "function_call")
            put("call_id", call.id)
            put("name", call.name)
            put("arguments", call.arguments)
        }) }
    }
    AgentRole.Tool -> message.toolResults.map { result -> buildJsonObject {
        put("type", "function_call_output")
        put("call_id", result.callId)
        put("output", result.content)
    } }
}

// Accumulates Responses API SSE events into an AgentResponse. Terminal state
// comes from the events themselves (completed/failed/incomplete) — this
// stream has no [DONE] sentinel, so EOF without a terminal event is a failure.
internal class ResponsesAgentStream(private val onDelta: (String) -> Unit) {
    private data class PendingCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    private val text = StringBuilder()
    private val toolCalls = sortedMapOf<Int, PendingCall>()
    private var usage: AgentUsage? = null
    private var terminalError: ProviderError? = null
    private var completed = false
    private var itemDone = false

    fun accept(payload: String) {
        val root = parseJsonObject(payload) ?: throw ProviderError.InvalidResponse("invalid stream event")
        when (root["type"]?.str()) {
            "response.output_text.delta" -> {
                root["delta"]?.str()?.takeIf { it.isNotEmpty() }?.let { value ->
                    text.append(value); onDelta(value)
                }
            }
            "response.output_text.done", "response.function_call_arguments.done" -> {
                itemDone = true
            }
            "response.completed", "response.done" -> {
                completed = true
                root["response"]?.obj()?.get("usage")?.obj()?.let { usage = responsesUsage(it) }
            }
            "response.failed" -> {
                // Provider-specific error mapping lives here (rivet §13):
                // subscription_sharing_* codes never reach the generic httpError().
                val error = root["response"]?.obj()?.get("error")?.obj()
                val code = error?.get("code")?.str()
                val message = error?.get("message")?.str()
                terminalError = when (code) {
                    "subscription_sharing_usage_limit_exceeded" -> ProviderError.UsageLimit()
                    else -> providerMessage(message ?: "request failed", code)
                }
            }
            "response.incomplete" -> {
                val reason = root["response"]?.obj()
                    ?.get("incomplete_details")?.obj()?.get("reason")?.str()
                terminalError = ProviderError.IncompleteGeneration(reason ?: "incomplete")
            }
            "response.output_item.added" -> {
                val item = root["item"]?.obj() ?: return
                if (item["type"]?.str() != "function_call") return
                val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: return
                val pending = toolCalls.getOrPut(index) { PendingCall() }
                pending.id = item["call_id"]?.str() ?: item["id"]?.str()
                pending.name = item["name"]?.str()
                item["arguments"]?.str()?.let { pending.arguments.append(it) }
            }
            "response.output_item.done" -> {
                val item = root["item"]?.obj() ?: return
                val type = item["type"]?.str()
                if (type == "function_call") {
                    val index = root["output_index"]?.jsonPrimitive?.intOrNull
                    if (index != null) {
                        val pending = toolCalls.getOrPut(index) { PendingCall() }
                        if (pending.id == null) pending.id = item["call_id"]?.str() ?: item["id"]?.str()
                        if (pending.name == null) pending.name = item["name"]?.str()
                        if (pending.arguments.isEmpty()) {
                            item["arguments"]?.str()?.let { pending.arguments.append(it) }
                        }
                    }
                }
                if (type == "message" || type == "function_call") {
                    itemDone = true
                }
            }
            "response.function_call_arguments.delta" -> {
                val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: return
                val pending = toolCalls.getOrPut(index) { PendingCall() }
                root["delta"]?.str()?.let { pending.arguments.append(it) }
            }
        }
    }

    fun response(): AgentResponse {
        terminalError?.let { err ->
            if (err is ProviderError.IncompleteGeneration && err.isLengthCut && toolCalls.isNotEmpty()) {
                val truncatedCalls = toolCalls.entries.mapIndexed { idx, (_, pending) ->
                    val rawArgs = pending.arguments.toString()
                    val safeArgs = rawArgs.takeIf {
                        it.isNotBlank() && jsonNestingWithinLimit(it, 32) && parseJsonObject(it) != null
                    } ?: "{}"
                    AgentToolCall(
                        id = pending.id?.takeIf { it.isNotBlank() } ?: "_rivet_truncated_${idx + 1}",
                        name = pending.name?.takeIf { it.isNotBlank() } ?: "unknown_tool",
                        arguments = safeArgs,
                    )
                }
                throw ProviderError.IncompleteGeneration(
                    reason = err.reason,
                    partialText = text.toString(),
                    truncatedToolCalls = truncatedCalls,
                )
            }
            throw err
        }
        val finishedCleanly = completed || (itemDone && (text.isNotEmpty() || toolCalls.isNotEmpty()))
        if (!finishedCleanly) throw ProviderError.IncompleteGeneration("incomplete stream")
        return AgentResponse(
            text = text.toString(),
            toolCalls = toolCalls.values.map { pending ->
                AgentToolCall(
                    pending.id?.takeIf { it.isNotBlank() }
                        ?: throw ProviderError.InvalidResponse("tool id missing"),
                    pending.name?.takeIf { it.isNotBlank() }
                        ?: throw ProviderError.InvalidResponse("tool name missing"),
                    pending.arguments.toString(),
                )
            },
            usage = usage,
        )
    }
}

private fun responsesUsage(usage: JsonObject): AgentUsage? {
    fun JsonObject.count(key: String): Long? =
        get(key)?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
    val input = usage.count("input_tokens")
    val output = usage.count("output_tokens")
    val total = usage.count("total_tokens")
    if (input == null && output == null && total == null) return null
    return AgentUsage(input, output, totalTokens = total)
}

// SSE reader for the Responses API: same structure, bounds, and cancellation
// semantics as http.sse(), but terminal state is event-driven (the
// accumulator records completed/failed) instead of a [DONE] sentinel.
internal suspend fun OkHttpClient.responsesSse(
    request: Request,
    progressDeadlineMs: Long = SSE_PROGRESS_DEADLINE_MS,
    clockMillis: () -> Long = ::monotonicMillis,
    onEvent: (String) -> Unit,
) {
    suspendCancellableCoroutine<Unit> { cont ->
        val call = newCall(request)
        val terminal = AtomicBoolean(false)
        cont.invokeOnCancellation {
            terminal.set(true)
            call.cancel()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (terminal.compareAndSet(false, true)) {
                    cont.resumeWithException(networkError(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw httpError(it.code, it.errorText())
                        val source = it.body?.source() ?: throw ProviderError.InvalidResponse("no body")
                        var responseBytes = 0
                        var lastProgressAt = clockMillis()
                        while (!terminal.get()) {
                            val line = try {
                                source.readUtf8LineStrict(MAX_SSE_LINE_BYTES.toLong())
                            } catch (_: EOFException) {
                                if (source.buffer.size > MAX_SSE_LINE_BYTES) {
                                    throw ProviderError.ResponseTooLarge()
                                }
                                break
                            }
                            val now = clockMillis()
                            if (now - lastProgressAt > progressDeadlineMs) {
                                throw ProviderError.Timeout()
                            }
                            responseBytes += line.toByteArray(Charsets.UTF_8).size + 2
                            if (responseBytes > MAX_SSE_RESPONSE_BYTES) {
                                throw ProviderError.ResponseTooLarge()
                            }
                            if (line.startsWith("data:")) {
                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                if (!terminal.get() && payload.isNotEmpty()) {
                                    lastProgressAt = now
                                    onEvent(payload)
                                }
                            }
                        }
                        if (terminal.compareAndSet(false, true)) cont.resume(Unit)
                    } catch (e: IOException) {
                        if (terminal.compareAndSet(false, true)) {
                            cont.resumeWithException(networkError(e))
                        }
                    } catch (e: ProviderError) {
                        if (terminal.compareAndSet(false, true)) cont.resumeWithException(e)
                    } catch (_: Exception) {
                        if (terminal.compareAndSet(false, true)) {
                            cont.resumeWithException(ProviderError.InvalidResponse("malformed stream event"))
                        }
                    }
                }
            }
        })
    }
}
