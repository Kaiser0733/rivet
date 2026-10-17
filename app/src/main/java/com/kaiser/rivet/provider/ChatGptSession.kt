package com.kaiser.rivet.provider

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Loopback OAuth listener + sign-in orchestrator for Sign in with ChatGPT.
// The listener binds 127.0.0.1 on an ephemeral port BEFORE the caller opens
// the browser; the orchestrator never opens anything itself — it hands the
// authorize URL to [onAuthorizeUrl] and suspends for the callback.

sealed interface ChatGptCallback {
    data class Code(
        val code: String,
        val state: String,
        val clientId: String,
        val scope: String,
    ) : ChatGptCallback
    data class Error(val error: String, val state: String?) : ChatGptCallback
    data object Timeout : ChatGptCallback
}

/** How long runSignIn waits for the user to finish in the browser. */
internal const val CHATGPT_SIGNIN_TIMEOUT_MS = 300_000L

/**
 * Raw ServerSocket on 127.0.0.1 (com.sun.net.httpserver is not on Android).
 * [port] 0 picks an ephemeral port; read the bound port from [actualPort]
 * before building the authorize URL.
 */
class ChatGptLoopback(port: Int = 0) {
    private val server: ServerSocket = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress("127.0.0.1", port))
    }
    private val result = AtomicReference<ChatGptCallback?>()
    private val latch = CountDownLatch(1)
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

    /** Suspends until the callback arrives or [timeoutMs] elapses. */
    suspend fun awaitCallback(timeoutMs: Long = CHATGPT_SIGNIN_TIMEOUT_MS): ChatGptCallback =
        withContext(Dispatchers.IO) {
            val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!arrived) ChatGptCallback.Timeout else result.get() ?: ChatGptCallback.Timeout
        }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) { }
        acceptThread?.interrupt()
    }

    private fun acceptLoop() {
        while (running && result.get() == null) {
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
        if (!target.startsWith(CHATGPT_CALLBACK_PATH)) {
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
        if (result.compareAndSet(null, callback)) latch.countDown()
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

/**
 * Runs the full sign-in: PKCE -> loopback -> authorize URL (handed to the
 * caller via [onAuthorizeUrl] for browser opening) -> callback -> token
 * exchange -> ID-token validation -> scope gate. The listener is always
 * stopped before returning.
 *
 * [clientId] is "dynamic_agent_client" for first registration; pass the saved
 * issued client id when re-running OAuth after a dead refresh.
 */
suspend fun runSignIn(
    hostId: String,
    onAuthorizeUrl: (String) -> Unit,
    clientId: String = CHATGPT_DYNAMIC_CLIENT_ID,
): ChatGptSignInResult {
    val verifier = generateCodeVerifier()
    val state = randomToken()
    val nonce = randomToken()
    val loopback = ChatGptLoopback()
    try {
        loopback.start()
        val port = loopback.actualPort
        val redirectUri = "http://127.0.0.1:$port$CHATGPT_CALLBACK_PATH"
        onAuthorizeUrl(
            buildAuthorizeUrl(
                clientId = clientId,
                hostId = hostId,
                port = port,
                state = state,
                nonce = nonce,
                challenge = codeChallenge(verifier),
            ),
        )
        when (val cb = loopback.awaitCallback()) {
            is ChatGptCallback.Timeout ->
                return ChatGptSignInResult.NetworkError("Sign-in timed out waiting for the browser callback")
            is ChatGptCallback.Error ->
                return if (cb.error == "access_denied") ChatGptSignInResult.UserCancelled
                else ChatGptSignInResult.InvalidCallback
            is ChatGptCallback.Code -> {
                if (cb.state != state) return ChatGptSignInResult.InvalidCallback
                if (cb.clientId.isEmpty()) return ChatGptSignInResult.InvalidCallback
                // The issued client id comes from the callback — never persist
                // "dynamic_agent_client".
                return when (val tokens = exchangeCode(cb.code, verifier, redirectUri, cb.clientId)) {
                    is TokenResult.NetworkFailed ->
                        ChatGptSignInResult.NetworkError("Token exchange failed: network")
                    is TokenResult.Failed ->
                        ChatGptSignInResult.NetworkError("Token exchange failed: ${tokens.error}")
                    is TokenResult.Ok -> {
                        val jwks = fetchJwks()
                            ?: return ChatGptSignInResult.NetworkError("Could not fetch signing keys")
                        val sub = validateIdToken(tokens.idToken, cb.clientId, nonce, jwks)
                            ?: return ChatGptSignInResult.InvalidCallback
                        if (!hasPlanScope(tokens.scope)) {
                            return ChatGptSignInResult.PlanNotGranted
                        }
                        ChatGptSignInResult.Success(
                            ChatGptBundle(
                                clientId = cb.clientId,
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                                idToken = tokens.idToken,
                                expiresInSec = tokens.expiresInSec,
                                savedAtMs = System.currentTimeMillis(),
                                scopes = tokens.scope.split(' ').filter { it.isNotEmpty() },
                                sub = sub,
                                email = idTokenEmail(tokens.idToken),
                            ),
                        )
                    }
                }
            }
        }
    } finally {
        loopback.stop()
    }
}

/**
 * Refreshes an expired access token. Returns the new bundle (with the
 * ROTATED refresh token — persist it) or null when reauth is required.
 */
suspend fun refreshBundle(bundle: ChatGptBundle): ChatGptBundle? {
    return when (val tokens = refreshTokens(bundle.clientId, bundle.refreshToken)) {
        // Null covers both transient network failure and dead refresh token;
        // the caller treats null as "reauth required" (safe direction).
        is TokenResult.NetworkFailed -> null
        is TokenResult.Failed -> null
        is TokenResult.Ok -> bundle.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            idToken = tokens.idToken,
            expiresInSec = tokens.expiresInSec,
            savedAtMs = System.currentTimeMillis(),
            scopes = tokens.scope.split(' ').filter { it.isNotEmpty() },
        )
    }
}
