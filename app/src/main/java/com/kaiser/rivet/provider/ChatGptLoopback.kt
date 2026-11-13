package com.kaiser.rivet.provider

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// Loopback OAuth listener + callback model for Sign in with ChatGPT.
// Extracted free of Android dependencies so cancellation and HTTP parsing
// tests run directly on the JVM.

sealed interface ChatGptCallback {
    data class Code(
        val code: String,
        val state: String,
        val clientId: String,
        val scope: String,
    ) : ChatGptCallback
    data class Error(val error: String, val state: String?) : ChatGptCallback
    data object Timeout : ChatGptCallback
    /** The waiter was cancelled via [ChatGptSignInSession.cancel]. */
    data object Cancelled : ChatGptCallback
}

/** How long runSignIn waits for the user to finish in the browser. */
internal const val CHATGPT_SIGNIN_TIMEOUT_MS = 300_000L

/**
 * Raw ServerSocket on 127.0.0.1 (com.sun.net.httpserver is not on Android).
 * [port] 0 picks an ephemeral port; read the bound port from [actualPort]
 * before building the authorize URL.
 */
class ChatGptLoopback(
    port: Int = 0,
    private val callbackPath: String = "/auth/callback",
) {
    private val server: ServerSocket = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress("127.0.0.1", port))
    }
    private val resultDeferred = CompletableDeferred<ChatGptCallback>()
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    val actualPort: Int
        get() = server.localPort

    fun start() {
        running = true
        acceptThread = Thread(::acceptLoop, "ChatGptLoopback").apply {
            isDaemon = true
            start()
        }
    }

    /** Suspends until the callback arrives, [timeoutMs] elapses, or the coroutine is cancelled. */
    suspend fun awaitCallback(timeoutMs: Long = CHATGPT_SIGNIN_TIMEOUT_MS): ChatGptCallback =
        withTimeoutOrNull(timeoutMs) { resultDeferred.await() } ?: ChatGptCallback.Timeout

    /**
     * Aborts a waiting sign-in immediately: the waiter observes [Cancelled]
     * and the server socket is closed now instead of lingering until the
     * await timeout. Safe to call when idle.
     */
    fun cancel() {
        resultDeferred.complete(ChatGptCallback.Cancelled)
        stop()
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) { }
        acceptThread?.interrupt()
    }

    private fun acceptLoop() {
        while (running && !resultDeferred.isCompleted) {
            val client: Socket = try {
                server.accept()
            } catch (_: Exception) {
                return // stopped
            }
            try {
                handleCallback(client)
            } catch (_: Exception) {
                // Malformed request; keep listening for the real callback.
            } finally {
                try { client.close() } catch (_: Exception) { }
            }
        }
    }

    private fun handleCallback(client: Socket) {
        client.soTimeout = CALLBACK_SOCKET_TIMEOUT_MS
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] != "GET") {
            respond(client.getOutputStream(), 405, "Method Not Allowed")
            return
        }
        val target = parts[1]
        if (!target.startsWith(callbackPath)) {
            // Not our callback (e.g. favicon); stay alive for the real one.
            respond(client.getOutputStream(), 404, "Not Found")
            return
        }
        // Drain headers before responding.
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val params = parseQuery(target.substringAfter('?', "").ifEmpty { null })
        val callback = when (val error = params["error"]) {
            null -> {
                val code = params["code"]
                val state = params["state"]
                if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
                    ChatGptCallback.Error("missing_code", state)
                } else {
                    ChatGptCallback.Code(
                        code = code,
                        state = state,
                        clientId = params["client_id"].orEmpty(),
                        scope = params["scope"].orEmpty(),
                    )
                }
            }
            else -> ChatGptCallback.Error(error, params["state"])
        }
        // Minimal close-tab page; one line of English, no resources.
        val page = "<html><body><p>You can close this tab and return to Rivet.</p></body></html>"
        val bytes = page.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
        out.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
        out.write("Cache-Control: no-store\r\n".toByteArray(Charsets.US_ASCII))
        out.write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
        resultDeferred.complete(callback)
    }

    private fun respond(out: OutputStream, code: Int, reason: String) {
        val body = "$reason\n".toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null
            else {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    companion object {
        private const val CALLBACK_SOCKET_TIMEOUT_MS = 10_000
    }
}
