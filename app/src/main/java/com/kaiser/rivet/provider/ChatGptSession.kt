package com.kaiser.rivet.provider

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 * JDK HttpServer on 127.0.0.1. [port] 0 picks an ephemeral port; read the
 * bound port from [actualPort] before building the authorize URL.
 */
class ChatGptLoopback(port: Int = 0) {
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val result = AtomicReference<ChatGptCallback?>()
    private val latch = CountDownLatch(1)

    val actualPort: Int
        get() = server.address.port

    fun start() {
        server.createContext(CHATGPT_CALLBACK_PATH) { exchange ->
            try {
                if (exchange.requestMethod != "GET") {
                    exchange.sendResponseHeaders(405, -1)
                    return@createContext
                }
                val params = parseQuery(exchange.requestURI.rawQuery)
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
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                if (result.compareAndSet(null, callback)) latch.countDown()
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    /** Suspends until the callback arrives or [timeoutMs] elapses. */
    suspend fun awaitCallback(timeoutMs: Long = CHATGPT_SIGNIN_TIMEOUT_MS): ChatGptCallback =
        withContext(Dispatchers.IO) {
            val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!arrived) ChatGptCallback.Timeout else result.get() ?: ChatGptCallback.Timeout
        }

    fun stop() {
        server.stop(0)
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
}

/**
 * Runs the full sign-in: PKCE -> loopback -> authorize URL (handed to the
 * caller via [onAuthorizeUrl] for browser opening) -> callback -> token
 * exchange -> ID-token validation -> scope gate. The listener is always
 * stopped before returning.
 */
suspend fun runSignIn(
    hostId: String,
    onAuthorizeUrl: (String) -> Unit,
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
                clientId = CHATGPT_DYNAMIC_CLIENT_ID,
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
