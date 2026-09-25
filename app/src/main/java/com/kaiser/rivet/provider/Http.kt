package com.kaiser.rivet.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
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
internal const val MAX_SSE_RESPONSE_BYTES = 1024 * 1024
// Model-list metadata is small; cap it at the same aggregate size as streamed replies.
internal const val MAX_PROVIDER_JSON_BODY_BYTES = 1024 * 1024
private const val MAX_ERROR_BODY_BYTES = 64 * 1024

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
    val obj = Json.parseToJsonElement(body ?: return null).jsonObject
    when {
        "error" in obj -> obj["error"]!!.jsonObject["message"]?.jsonPrimitive?.content
        "message" in obj -> obj["message"]?.jsonPrimitive?.content
        else -> null
    }
} catch (e: Exception) {
    null
}

private fun errorBodyCode(body: String?): String? = try {
    val error = Json.parseToJsonElement(body ?: return null).jsonObject["error"]?.jsonObject
    error?.get("code")?.jsonPrimitive?.content ?: error?.get("type")?.jsonPrimitive?.content
} catch (e: Exception) {
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
        detail?.let { it.contains("model", ignoreCase = true) && code in intArrayOf(400, 404) } == true ->
            ProviderError.ModelNotFound(extractModelName(detail))
        code == 404 -> ProviderError.UnsupportedEndpoint()
        detail != null -> ProviderError.ProviderMessage(detail)
        else -> ProviderError.ProviderMessage("HTTP $code")
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
        "maximum context length", "prompt is too long", "input token count exceeds", "request too large for context")
        .any { it in value }
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

fun networkError(e: IOException): ProviderError = when {
    e.cause is SecurityException -> ProviderError.Network("permission")
    e is UnknownHostException -> ProviderError.Network("dns")
    e is ConnectException -> ProviderError.Network("connect")
    e is SocketTimeoutException -> ProviderError.Timeout()
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
internal suspend fun OkHttpClient.sse(request: Request, onEvent: (String) -> Unit): Boolean =
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
                        while (!terminal.get()) {
                            val line = try {
                                source.readUtf8LineStrict(MAX_SSE_LINE_BYTES.toLong())
                            } catch (_: EOFException) {
                                if (source.buffer.size > MAX_SSE_LINE_BYTES) {
                                    throw ProviderError.ResponseTooLarge()
                                }
                                break
                            }
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
                                if (!terminal.get() && payload.isNotEmpty()) {
                                    onEvent(payload)
                                }
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
