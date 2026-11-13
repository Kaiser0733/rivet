package com.kaiser.rivet.provider

import com.kaiser.rivet.storage.HostIdStore

// Sign-in orchestrator for Sign in with ChatGPT.
// Loopback listener and callback model live in ChatGptLoopback.kt.

/**
 * Handle for an in-flight sign-in. Create one per attempt, pass it to
 * [runSignIn], and keep it while the attempt is active. [cancel] aborts the
 * attempt immediately — the loopback listener stops at once instead of
 * lingering until the await timeout, and the waiter observes cancellation
 * (surfaced as [ChatGptSignInResult.UserCancelled]).
 */
class ChatGptSignInSession {
    private val loopback = ChatGptLoopback(callbackPath = CHATGPT_CALLBACK_PATH)
    internal fun loopback(): ChatGptLoopback = loopback
    fun cancel() = loopback.cancel()
}

/**
 * Runs the full sign-in: PKCE -> loopback -> authorize URL (handed to the
 * caller via [onAuthorizeUrl] for browser opening) -> callback -> token
 * exchange -> ID-token validation -> scope gate. The listener is always
 * stopped before returning.
 *
 * [clientId] is "dynamic_agent_client" for first registration; pass the saved
 * issued client id when re-running OAuth after a dead refresh.
 * [session] lets the caller abort early via [ChatGptSignInSession.cancel].
 * [hostIds] persists the issued client id from the callback BEFORE the token
 * exchange, so a retry after an exchange failure (e.g. invalid_grant) reuses
 * it; the pending id is cleared once an exchange succeeds.
 */
suspend fun runSignIn(
    hostId: String,
    onAuthorizeUrl: (String) -> Unit,
    clientId: String = CHATGPT_DYNAMIC_CLIENT_ID,
    session: ChatGptSignInSession = ChatGptSignInSession(),
    hostIds: HostIdStore,
): ChatGptSignInResult {
    val verifier = generateCodeVerifier()
    val state = randomToken()
    val nonce = randomToken()
    val loopback = session.loopback()
    try {
        loopback.setExpectedState(state)
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
                return ChatGptSignInResult.TimedOut
            is ChatGptCallback.Cancelled ->
                return ChatGptSignInResult.UserCancelled
            is ChatGptCallback.Error ->
                return if (cb.error == "access_denied") ChatGptSignInResult.UserCancelled
                else ChatGptSignInResult.InvalidCallback
            is ChatGptCallback.Code -> {
                if (cb.state != state) return ChatGptSignInResult.InvalidCallback
                if (cb.clientId.isEmpty()) return ChatGptSignInResult.InvalidCallback
                // The issued client id comes from the callback — never persist
                // "dynamic_agent_client". Save it BEFORE the exchange: if the
                // exchange fails, the retry reuses this id per the SIWC doc.
                hostIds.savePendingClientId(cb.clientId)
                return when (val tokens = exchangeCode(cb.code, verifier, redirectUri, cb.clientId)) {
                    is TokenResult.NetworkFailed ->
                        ChatGptSignInResult.NetworkError("Token exchange failed: network")
                    is TokenResult.Failed ->
                        ChatGptSignInResult.ServerRejected(tokens.error)
                    is TokenResult.Ok -> {
                        // Exchange consumed the code; the pending id's job is
                        // done — the bundle below takes over from here.
                        hostIds.clearPendingClientId()
                        val idToken = tokens.idToken ?: return ChatGptSignInResult.InvalidCallback
                        val jwks = fetchJwks()
                            ?: return ChatGptSignInResult.NetworkError("Could not fetch signing keys")
                        val sub = validateIdToken(idToken, cb.clientId, nonce, jwks)
                            ?: return ChatGptSignInResult.InvalidCallback
                        if (!hasPlanScope(tokens.scope)) {
                            return ChatGptSignInResult.PlanNotGranted
                        }
                        ChatGptSignInResult.Success(
                            ChatGptBundle(
                                clientId = cb.clientId,
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                                idToken = idToken,
                                expiresInSec = tokens.expiresInSec,
                                savedAtMs = System.currentTimeMillis(),
                                scopes = tokens.scope.split(' ').filter { it.isNotEmpty() },
                                sub = sub,
                                email = idTokenEmail(idToken),
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

sealed interface ChatGptRefreshResult {
    data class Renewed(val bundle: ChatGptBundle) : ChatGptRefreshResult
    data object ReauthRequired : ChatGptRefreshResult
    data object NetworkFailed : ChatGptRefreshResult
}

/**
 * Refreshes an expired access token while preserving existing scopes/email when
 * omitted on refresh and distinguishing dead refresh tokens from transient failures.
 */
suspend fun refreshBundleResult(bundle: ChatGptBundle): ChatGptRefreshResult {
    return when (val tokens = refreshTokens(bundle.clientId, bundle.refreshToken)) {
        is TokenResult.Ok -> {
            val merged = mergeRefreshedBundleFields(
                currentIdToken = bundle.idToken,
                currentScopes = bundle.scopes,
                currentEmail = bundle.email,
                tokens = tokens,
                extractEmail = ::idTokenEmail,
            )
            ChatGptRefreshResult.Renewed(
                bundle.copy(
                    accessToken = merged.accessToken,
                    refreshToken = merged.refreshToken,
                    idToken = merged.idToken,
                    expiresInSec = merged.expiresInSec,
                    savedAtMs = System.currentTimeMillis(),
                    scopes = merged.scopes,
                    email = merged.email,
                ),
            )
        }
        else -> when (classifyRefreshFailure(tokens)) {
            ChatGptRefreshFailure.REAUTH_REQUIRED -> ChatGptRefreshResult.ReauthRequired
            ChatGptRefreshFailure.TRANSIENT -> ChatGptRefreshResult.NetworkFailed
        }
    }
}

/**
 * Refreshes an expired access token. Returns the new bundle (with the
 * ROTATED refresh token — persist it) or null when refresh failed.
 */
suspend fun refreshBundle(bundle: ChatGptBundle): ChatGptBundle? =
    (refreshBundleResult(bundle) as? ChatGptRefreshResult.Renewed)?.bundle
