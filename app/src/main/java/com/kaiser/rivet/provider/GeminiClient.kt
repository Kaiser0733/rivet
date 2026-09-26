package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Gemini generateContent in its native shape: model in the path, key in the
// x-goog-api-key header, SSE chunks that each carry a full response object.
// The editor exposes no Gemini reasoning control and this transport emits no
// reasoning configuration.
internal class GeminiClient(
    private val config: ProviderConfig,
    private val apiKey: String,
) : ProviderClient {

    override suspend fun listModels(): List<ModelInfo> {
        val request = base(Endpoints.geminiModels(config.baseUrl) + "?pageSize=1000").get().build()
        http.quick().await(request).use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.errorText())
            val text = r.readBoundedBody() ?: throw ProviderError.InvalidResponse("no body")
            val models = parseJsonObject(text)?.get("models")?.arr() ?: throw ProviderError.InvalidResponse("not a JSON object")
            return models.mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                if ("generateContent" !in (o["supportedGenerationMethods"]?.arr()
                        ?.mapNotNull { it.str() } ?: emptyList())) return@mapNotNull null
                val name = o["name"]?.str() ?: return@mapNotNull null
                ModelInfo(name.removePrefix("models/"), o["displayName"]?.str() ?: name,
                    o["inputTokenLimit"].positiveInt())
            }.sortedBy { it.id.lowercase() }
        }
    }

    override suspend fun testConnection(): TestResult {
        try {
            val models = listModels()
            return TestResult(true, "Reachable and authenticated. ${models.size} models listed.")
        } catch (e: ProviderError) {
            return TestResult(false, e.text())
        }
    }

    override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                request.messages.forEach { m ->
                    add(geminiMessage(m))
                }
            })
            if (request.system.isNotEmpty()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.system) })
                    })
                })
            }
            if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject { put("functionDeclarations", buildJsonArray {
                    request.tools.forEach { tool -> add(buildJsonObject {
                        put("name", tool.name); put("description", tool.description)
                        put("parametersJsonSchema", tool.parameters)
                    }) }
                }) })
            })
        }
        val httpRequest = base(Endpoints.geminiChat(config.baseUrl, request.model))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val stream = GeminiAgentStream(onDelta)
        http.sse(httpRequest) { payload ->
            stream.accept(payload)
        }
        return stream.response()
    }

    private fun base(url: String): Request.Builder {
        val b = requestBuilder(url)
            .header("x-goog-api-key", apiKey)
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

private fun geminiMessage(message: AgentMessage): JsonObject {
    val state = message.transportState?.let { raw ->
        try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
    } ?: buildJsonObject {}
    val signatures = state.takeIf { it["provider"]?.str() == "gemini" }
        ?.get("call_signatures")?.obj() ?: buildJsonObject {}
    val textSignature = state.takeIf { it["provider"]?.str() == "gemini" }
        ?.get("text_signature")?.str()
    return when (message.role) {
        AgentRole.User -> buildJsonObject {
            put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", message.text) }) })
        }
        AgentRole.Assistant -> buildJsonObject {
            put("role", "model")
            put("parts", buildJsonArray {
                if (message.text.isNotEmpty() || textSignature != null) add(buildJsonObject {
                    put("text", message.text)
                    textSignature?.let { put("thoughtSignature", it) }
                })
                message.toolCalls.forEach { call ->
                    val args = try { Json.parseToJsonElement(call.arguments) as? JsonObject } catch (_: Exception) { null }
                        ?: throw ProviderError.InvalidResponse("invalid function arguments")
                    add(buildJsonObject {
                        put("functionCall", buildJsonObject {
                            if (!call.id.startsWith(GEMINI_LOCAL_ID_PREFIX)) put("id", call.id)
                            put("name", call.name); put("args", args)
                        })
                        signatures[call.id]?.str()?.let { put("thoughtSignature", it) }
                    })
                }
            })
        }
        AgentRole.Tool -> buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray { message.toolResults.forEach { result ->
                val parsed = try { Json.parseToJsonElement(result.content) } catch (_: Exception) { null }
                val response = parsed as? JsonObject ?: buildJsonObject { put("output", result.content) }
                add(buildJsonObject { put("functionResponse", buildJsonObject {
                    if (!result.callId.startsWith(GEMINI_LOCAL_ID_PREFIX)) put("id", result.callId)
                    put("name", result.name); put("response", response)
                }) })
            } })
        }
    }
}

private class GeminiAgentStream(private val onDelta: (String) -> Unit) {
    private val text = StringBuilder()
    private val calls = mutableListOf<AgentToolCall>()
    private val signatures = linkedMapOf<String, String>()
    private var textSignature: String? = null
    private var usage: com.kaiser.rivet.agent.AgentUsage? = null
    private var finished = false
    private var finishReason: String? = null

    fun accept(payload: String) {
        val root = parseJsonObject(payload) ?: throw ProviderError.InvalidResponse("invalid stream event")
        root["error"]?.obj()?.get("message")?.str()?.let {
            throw providerMessage(it, root["error"]?.obj()?.get("status")?.str())
        }
        geminiUsage(root)?.let { usage = it }
        val candidate = root["candidates"]?.arr()?.firstOrNull()?.obj()
        candidate?.get("finishReason")?.str()?.let {
            finishReason = it
            finished = true
        }
        val parts = candidate?.get("content")?.obj()?.get("parts")?.arr() ?: return
        parts.forEach { element ->
            val part = element.obj() ?: throw ProviderError.InvalidResponse("invalid response part")
            part["text"]?.str()?.let { value ->
                if (value.isNotEmpty()) { text.append(value); onDelta(value) }
            }
            val function = part["functionCall"]?.obj()
            if (function == null) {
                part["thoughtSignature"]?.str()?.let { textSignature = it }
            } else {
                val id = function["id"]?.str()?.takeIf { it.isNotBlank() }
                    ?: "$GEMINI_LOCAL_ID_PREFIX${calls.size + 1}"
                val name = function["name"]?.str()?.takeIf { it.isNotBlank() }
                    ?: throw ProviderError.InvalidResponse("function name missing")
                val args = function["args"]?.let { value ->
                    value.obj() ?: throw ProviderError.InvalidResponse("invalid function arguments")
                } ?: buildJsonObject {}
                calls += AgentToolCall(id, name, args.toString())
                part["thoughtSignature"]?.str()?.let { signatures[id] = it }
            }
        }
    }

    fun response(): AgentResponse {
        if (!finished) throw ProviderError.InvalidResponse("incomplete stream")
        if (finishReason != "STOP") {
            throw ProviderError.IncompleteGeneration(finishReason ?: "missing finish reason")
        }
        return AgentResponse(
            text.toString(),
            calls.toList(),
            signatures.takeIf { it.isNotEmpty() }.let { values ->
                if (values == null && textSignature == null) null else buildJsonObject {
                    put("provider", "gemini")
                    values?.let { callSignatures -> put("call_signatures", buildJsonObject {
                        callSignatures.forEach { (id, signature) -> put(id, signature) }
                    }) }
                    textSignature?.let { put("text_signature", it) }
                }.toString()
            },
            usage,
        )
    }
}

private const val GEMINI_LOCAL_ID_PREFIX = "_rivet_gemini_"
