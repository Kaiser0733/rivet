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
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// One client, one connection pool for the whole app; per-call timeouts are
// derived with quick().
internal val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS) // SSE streams idle between deltas
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun OkHttpClient.quick(): OkHttpClient =
    newBuilder().readTimeout(20, TimeUnit.SECONDS).build()


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

fun httpError(code: Int, body: String?): ProviderError {
    val detail = errorBodyText(body)
    return when {
        code == 401 -> ProviderError.Unauthorized()
        code == 403 -> ProviderError.Forbidden()
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

private fun extractModelName(detail: String): String {
    Regex("""['"`]([\w.\-/:]+)['"`]""").find(detail)?.groupValues?.get(1)?.let { return it }
    return detail.take(60)
}

fun networkError(e: IOException): ProviderError = when (e) {
    is UnknownHostException -> ProviderError.Network("dns")
    is ConnectException -> ProviderError.Network("connect")
    is SocketTimeoutException -> ProviderError.Timeout()
    is SSLException -> ProviderError.Network("tls")
    else -> ProviderError.Network(e.javaClass.simpleName)
}

internal suspend fun OkHttpClient.await(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) cont.resumeWithException(ProviderError.Cancelled)
                else cont.resumeWithException(networkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // If cancellation won the race, resume is a no-op and the
                // body must be closed here or it leaks.
                if (!cont.isActive) response.close() else cont.resume(response)
            }
        })
    }

// Reads an SSE body, invoking onEvent for each non-empty data payload.
// Coroutine cancellation cancels the OkHttp call, which unblocks the reader
// loop and resumes normally — cancellation never surfaces as an error.
internal suspend fun OkHttpClient.sse(request: Request, onEvent: (String) -> Unit) {
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) cont.resume(Unit)
                else cont.resumeWithException(networkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw httpError(it.code, it.body?.string())
                        val source = it.body?.source() ?: throw ProviderError.InvalidResponse("no body")
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data:")) {
                                val payload = line.removePrefix("data:").trim()
                                if (payload.isNotEmpty() && payload != "[DONE]") onEvent(payload)
                            }
                        }
                        cont.resume(Unit)
                    } catch (e: IOException) {
                        if (call.isCanceled()) cont.resume(Unit)
                        else cont.resumeWithException(networkError(e))
                    } catch (e: ProviderError) {
                        cont.resumeWithException(e)
                    }
                }
            }
        })
    }
}
