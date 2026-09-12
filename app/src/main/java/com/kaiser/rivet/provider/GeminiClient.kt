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
            if (!r.isSuccessful) throw httpError(r.code, r.body?.string())
            val text = r.body?.string() ?: throw ProviderError.InvalidResponse("no body")
            val models = parseJsonObject(text)?.get("models")?.arr() ?: throw ProviderError.InvalidResponse("not a JSON object")
            return models.mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                if ("generateContent" !in (o["supportedGenerationMethods"]?.arr()
                        ?.mapNotNull { it.str() } ?: emptyList())) return@mapNotNull null
                val name = o["name"]?.str() ?: return@mapNotNull null
                ModelInfo(name.removePrefix("models/"), o["displayName"]?.str() ?: name)
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

    override suspend fun streamChat(request: ChatRequest, onDelta: (String) -> Unit): String {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role.wireName == "assistant") "model" else "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", m.text) })
                        })
                    })
                }
            })
            if (request.system.isNotEmpty()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.system) })
                    })
                })
            }
        }
        val httpRequest = base(Endpoints.geminiChat(config.baseUrl, request.model))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val out = StringBuilder()
        http.sse(httpRequest) { payload ->
            geminiDelta(payload)?.let {
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
            .header("x-goog-api-key", apiKey)
        config.headers.sanitized().forEach { b.header(it.name, it.value) }
        return b
    }
}

// Each SSE chunk is a full GenerateContentResponse; concatenate every text
// part of the first candidate. Error chunks ({"error":{...}}) surface their
// message as a stream failure instead of masquerading as content.
internal fun geminiDelta(payload: String): String? {
    val obj = try {
        Json.parseToJsonElement(payload).jsonObject
    } catch (e: Exception) {
        return null
    }
    obj["error"]?.obj()?.get("message")?.str()?.let { throw ProviderError.ProviderMessage(it) }
    return try {
        obj["candidates"]?.arr()?.firstOrNull()?.obj()
            ?.get("content")?.obj()?.get("parts")?.arr()
            ?.mapNotNull { it.obj()?.get("text")?.str() }
            ?.joinToString("")
    } catch (e: Exception) {
        null
    }
}
