package com.kaiser.rivet.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// One client, one connection pool for the whole app; per-call timeouts are
// derived with quick().
internal val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS) // SSE streams idle between deltas
    .writeTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: SecurityException) {
            // Android socket permission failures otherwise escape OkHttp's
            // callback path as an uncaught dispatcher-thread exception.
            throw IOException("socket permission denied", e)
        }
    }
    .build()

internal fun OkHttpClient.quick(): OkHttpClient =
    newBuilder().readTimeout(20, TimeUnit.SECONDS).build()

// Providers should return compact streamed deltas. These ceilings prevent a
// malformed endpoint from retaining an unbounded event or response on-device.
internal const val MAX_SSE_LINE_BYTES = 128 * 1024
internal const val MAX_SSE_RESPONSE_BYTES = 16 * 1024 * 1024
internal const val SSE_PROGRESS_DEADLINE_MS = 120_000L
// Large gateways (such as OpenRouter) return multi-megabyte model catalogs;
// allow up to 16 MiB on the wire and cap the retained model count in memory.
internal const val MAX_PROVIDER_JSON_BODY_BYTES = 16 * 1024 * 1024
internal const val MAX_RETAINED_MODELS = 2000
private const val MAX_ERROR_BODY_BYTES = 64 * 1024

internal fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

internal fun Response.readBoundedBody(limitBytes: Int = MAX_PROVIDER_JSON_BODY_BYTES): String? {
    require(limitBytes > 0)
    val responseBody = body ?: return null
    if (responseBody.contentLength() > limitBytes) throw ProviderError.ResponseTooLarge()
    val source = responseBody.source()
    val bytes = Buffer()
    val limit = limitBytes.toLong()
    while (true) {
        val count = source.read(bytes, (limit + 1 - bytes.size).coerceAtLeast(1L))
        if (count < 0) break
        if (bytes.size > limit) throw ProviderError.ResponseTooLarge()
    }
    return bytes.readUtf8()
}

internal fun Response.errorText(): String? {
    val source = body?.source() ?: return null
    val bytes = Buffer()
    var remaining = MAX_ERROR_BODY_BYTES + 1L
    while (remaining > 0) {
        val read = source.read(bytes, remaining)
        if (read < 0) break
        remaining -= read
    }
    val value = bytes.readUtf8()
    return value.take(MAX_ERROR_BODY_BYTES / 4).dropLastWhile { it.isHighSurrogate() }
}

// Best-effort extraction of a human-readable message from any of the error
// body shapes in use ({"error":{"message"}}, {"message"}).
private fun errorBodyText(body: String?): String? = try {
    val obj = parseJsonObject(body ?: return null) ?: return null
    when {
        "error" in obj -> obj["error"]!!.jsonObject["message"]?.jsonPrimitive?.content
        "message" in obj -> obj["message"]?.jsonPrimitive?.content
        else -> null
    }
} catch (e: IllegalArgumentException) {
    null
}

private fun errorBodyCode(body: String?): String? = try {
    val error = parseJsonObject(body ?: return null)?.get("error")?.jsonObject
    error?.get("code")?.jsonPrimitive?.content ?: error?.get("type")?.jsonPrimitive?.content
} catch (e: IllegalArgumentException) {
    null
}

fun httpError(code: Int, body: String?): ProviderError {
    val detail = errorBodyText(body)
    val providerCode = errorBodyCode(body)
    return when {
        code == 401 -> ProviderError.Unauthorized()
        code == 403 -> ProviderError.Forbidden()
        code in setOf(400, 413, 422) && isContextOverflow(body.orEmpty()) -> ProviderError.ContextOverflow()
        code == 429 && isUsageLimit(providerCode.orEmpty() + " " + detail.orEmpty()) -> ProviderError.UsageLimit()
        code == 429 && isResourceExhausted(detail.orEmpty()) -> ProviderError.ResourceExhausted()
        code == 429 -> ProviderError.RateLimited()
        code in 500..599 -> ProviderError.Server(code)
        // Wrong-model bodies arrive as 400 or 404 depending on the service
        // (OpenAI 400 "model 'x' does not exist", others 404 with the id
        // in the message); check the body before assuming endpoint shape.
        detail?.let { isModelNotFound(code, it) } == true ->
            ProviderError.ModelNotFound(extractModelName(detail))
        code == 404 -> ProviderError.UnsupportedEndpoint()
        detail != null -> ProviderError.ProviderMessage(detail, code)
        else -> ProviderError.ProviderMessage("HTTP $code", code)
    }
}

internal fun providerMessage(message: String, code: String? = null): ProviderError = when {
    isContextOverflow(listOfNotNull(code, message).joinToString(" ")) -> ProviderError.ContextOverflow()
    isUsageLimit(listOfNotNull(code, message).joinToString(" ")) -> ProviderError.UsageLimit()
    isResourceExhausted(listOfNotNull(code, message).joinToString(" ")) -> ProviderError.ResourceExhausted()
    else -> ProviderError.ProviderMessage(message)
}

private fun isUsageLimit(text: String): Boolean {
    val value = text.lowercase(java.util.Locale.ROOT)
    return "insufficient_quota" in value || "exceeded your current quota" in value ||
        "quota exceeded" in value || "billing hard limit" in value
}

private fun isContextOverflow(text: String): Boolean {
    val value = text.lowercase(java.util.Locale.ROOT)
    return listOf("context_length_exceeded", "model_context_window_exceeded", "context window exceeded",
        "context_window_exceeded", "maximum context length", "prompt is too long",
        "input token count exceeds", "request too large for context").any { it in value } ||
        Regex("""input token count\s*\(\s*[\d,]+\s*\)\s*exceeds\s*the maximum number of tokens allowed\s*\(\s*[\d,]+\s*\)""")
            .containsMatchIn(value) ||
        Regex("""input token count\s+is\s+[\d,]+\s+but\s+model\s+only\s+supports\s+up\s+to\s+[\d,]+""")
            .containsMatchIn(value) ||
        (("context window" in value || "context length" in value) &&
            ("exceed" in value || "too long" in value || "full" in value))
}

private fun isModelNotFound(code: Int, detail: String): Boolean {
    // A body that merely mentions "model" is not a missing model: "Invalid URL (GET /models)"
    // from a base URL without /v1 used to become "Model \"Invalid URL (GET /models)\" was not
    // found", walking the user into the model picker for a path problem. A model id has to
    // be named, either by one of the conventions below or by Anthropic's "model: <id>".
    if (code != 400 && code != 404) return false
    if (!detail.contains("model", ignoreCase = true)) return false
    val value = detail.lowercase(java.util.Locale.ROOT)
    return "model_not_found" in value || "unknown model" in value || "no such model" in value ||
        "could not find model" in value || (code == 404 && "model:" in value) ||
        ("model" in value && ("not found" in value || "does not exist" in value || "doesn't exist" in value))
}

private fun isResourceExhausted(text: String): Boolean {
    val value = text.lowercase(java.util.Locale.ROOT)
    return "resourceexhausted" in value || "resource_exhausted" in value ||
        "worker local total request limit reached" in value
}

private fun extractModelName(detail: String): String {
    Regex("""['"`]([\w.\-/:]+)['"`]""").find(detail)?.groupValues?.get(1)?.let { return it }
    return detail.take(60)
}

internal fun isUnsupportedReasoningError(error: ProviderError, requested: ReasoningLevel,
                                          effortWasSent: Boolean = true): Boolean {
    if (requested == ReasoningLevel.Default) return false
    if (error is ProviderError.UnsupportedConfiguration) return true
    val message = (error as? ProviderError.ProviderMessage)?.text ?: return false
    val value = message.lowercase(java.util.Locale.ROOT)
    val mentionsReasoning = listOf(
        "reasoning_effort", "reasoning effort", "reasoning.effort", "reasoning_level",
        "thinking_budget", "thinking budget", "thinking_config", "thinkingconfig",
        "budget_tokens", "output_config",
    ).any { it in value } ||
        (("reasoning" in value || "thinking" in value || "effort" in value) &&
            ("unsupported" in value || "not support" in value || "doesn't support" in value ||
                "does not support" in value || "invalid" in value || "unrecognized" in value ||
                "unknown" in value || "not allowed" in value || "allowed values" in value ||
                "valid values" in value || "must be one of" in value || "cannot be set" in value))
    if (mentionsReasoning) return true
    val wire = requested.wireName.lowercase(java.util.Locale.ROOT)
    if (wire in value && ("effort" in value || "reasoning" in value || "thinking" in value)) return true
    // Some endpoints reject an effort without naming it ("Invalid inference request. Check
    // the model's supported parameters and input limits."). Only a client-side rejection on
    // an effort that was actually sent may be read that way; a size problem or a complaint
    // about another field we send must not be blamed on the effort.
    //
    // The refusal can arrive twice as a ProviderMessage: from a non-2xx response, carrying
    // its status, and from an error event inside a 200 stream (providerMessage below), which
    // carries no status at all. The device showed the second shape, so a rule that demands a
    // 400 is blind exactly where it is needed.
    if (!effortWasSent) return false
    val status = (error as ProviderError.ProviderMessage).statusCode
    val clientSide = status == null || status in setOf(400, 422)
    return clientSide && looksLikeParameterRejection(value)
}

private val PARAMETER_REJECTION = listOf(
    "invalid inference request", "invalid request", "invalid parameter", "invalid value",
    "unsupported parameter", "unsupported value", "supported parameters", "bad request",
)

// An input that was too big is a size problem, not an effort problem.
private val SIZE_REJECTION = listOf(
    "too long", "too large", "too many tokens", "exceeds", "maximum context", "context length",
    "token count", "payload", "out of memory",
)

// A complaint about another request field must not be blamed on the effort either.
private val OTHER_FIELD_REJECTION = listOf(
    "temperature", "top_p", "top_k", "max_tokens", "max_output_tokens", "stream", "messages",
    "tools", "tool_choice", "image", "schema", "frequency_penalty", "presence_penalty",
    "response_format", "seed", "does not exist", "not found",
)

private fun looksLikeParameterRejection(value: String): Boolean =
    PARAMETER_REJECTION.any { it in value } &&
        SIZE_REJECTION.none { it in value } &&
        OTHER_FIELD_REJECTION.none { it in value }

fun networkError(e: IOException): ProviderError = when {
    e.cause is SecurityException -> ProviderError.Network("permission")
    e is UnknownHostException -> ProviderError.Network("dns")
    e is ConnectException -> ProviderError.Network("connect")
    e is SocketTimeoutException -> ProviderError.Timeout("socket")
    e is SSLException -> ProviderError.Network("tls")
    else -> ProviderError.Network(e.javaClass.simpleName)
}

internal fun requestBuilder(url: String): Request.Builder = try {
    Request.Builder().url(url)
} catch (e: IllegalArgumentException) {
    throw ProviderError.MalformedUrl(url)
}

internal suspend fun OkHttpClient.await(request: Request): Response = newCall(request).await()

internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        val terminal = AtomicBoolean(false)
        val deliveredResponse = AtomicReference<Response?>(null)
        cont.invokeOnCancellation {
            terminal.set(true)
            cancel()
            deliveredResponse.getAndSet(null)?.close()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (terminal.compareAndSet(false, true)) {
                    cont.resumeWithException(networkError(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                deliveredResponse.set(response)
                if (!terminal.compareAndSet(false, true)) {
                    deliveredResponse.compareAndSet(response, null)
                    response.close()
                    return
                }
                cont.resume(response) { response.close() }
            }
        })
    }

// Reads an SSE body, invoking onEvent for each non-empty data payload.
// Coroutine cancellation cancels the OkHttp call and remains a
// CancellationException; callback completion can never resume twice.
internal suspend fun OkHttpClient.sse(
    request: Request,
    progressDeadlineMs: Long = SSE_PROGRESS_DEADLINE_MS,
    clockMillis: () -> Long = ::monotonicMillis,
    onEvent: (String) -> Unit,
): Boolean =
    suspendCancellableCoroutine { cont ->
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
                        var completed = false
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
                            responseBytes += line.toByteArray(Charsets.UTF_8).size + 2
                            if (responseBytes > MAX_SSE_RESPONSE_BYTES) {
                                throw ProviderError.ResponseTooLarge()
                            }
                            if (line.startsWith("data:")) {
                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") {
                                    completed = true
                                    break
                                }
                                if (payload.isNotEmpty()) {
                                    // A real payload is progress, so it is accepted before
                                    // the deadline is judged: a model that thought for
                                    // longer than the budget and then answered must not
                                    // have that first token thrown away.
                                    lastProgressAt = now
                                    if (!terminal.get()) onEvent(payload)
                                    continue
                                }
                            }
                            // Bytes that are not a payload (comments, empty events) keep a
                            // dead stream looking alive, which is what the budget is for.
                            if (now - lastProgressAt > progressDeadlineMs) {
                                throw ProviderError.Timeout("progress")
                            }
                        }
                        if (terminal.compareAndSet(false, true)) cont.resume(completed)
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
