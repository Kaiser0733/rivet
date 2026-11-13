package com.kaiser.rivet.provider

import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Sign in with ChatGPT (SIWC) auth core: dynamic client registration + PKCE
// S256 + loopback redirect. No UI here; the UI layer (later step) opens the
// browser with buildAuthorizeUrl() and ChatGptSession drives the flow.
//
// Security invariants:
// - `state` is validated against the attempt that created it; mismatches abort.
// - The issued client_id comes from the callback — "dynamic_agent_client" is
//   NEVER persisted.
// - Refresh tokens ROTATE: every refresh response carries a replacement that
//   the caller must persist. Refreshes must not race.
// - ID-token `sub` is trusted only after RS256 JWKS signature verification.

internal const val CHATGPT_AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
internal const val CHATGPT_TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
internal const val CHATGPT_OPENID_CONFIG_URL = "https://auth.openai.com/.well-known/openid-configuration"
internal const val CHATGPT_ISSUER = "https://auth.openai.com"
internal const val CHATGPT_RESOURCE = "https://api.openai.com/v1"
internal const val CHATGPT_DYNAMIC_CLIENT_ID = "dynamic_agent_client"
internal const val CHATGPT_REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
internal const val CHATGPT_SCOPE =
    "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
internal const val CHATGPT_CALLBACK_PATH = "/auth/callback"

/**
 * Which client_id to open the authorize URL with: the saved bundle's issued
 * id first (reauth), then the id from the last completed consent — its token
 * exchange may have failed (e.g. invalid_grant), so the retry reuses it per
 * the SIWC doc instead of re-registering — then fresh registration as
 * dynamic_agent_client.
 */
fun resolveSignInClientId(bundleClientId: String?, pendingClientId: String?): String =
    if (!bundleClientId.isNullOrBlank()) bundleClientId
    else if (!pendingClientId.isNullOrBlank()) pendingClientId
    else CHATGPT_DYNAMIC_CLIENT_ID

// --- PKCE / random tokens -------------------------------------------------

/** 64 random bytes, base64url no-pad: 86 chars, inside the 43-128 range. */
fun generateCodeVerifier(): String {
    val bytes = ByteArray(64)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** S256 challenge for a verifier. */
fun codeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** Fresh `state`/`nonce` per attempt. */
fun randomToken(byteCount: Int = 32): String {
    val bytes = ByteArray(byteCount)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

// --- Authorize URL --------------------------------------------------------

/**
 * Builds the SIWC authorize URL. [clientId] is "dynamic_agent_client" on first
 * registration, the issued `oaiapp_...` id on reauth. [port] is the actual
 * loopback port the listener bound (ephemeral). The redirect uses the literal
 * 127.0.0.1 — never "localhost" — and the path is exactly /auth/callback.
 */
fun buildAuthorizeUrl(
    clientId: String,
    hostId: String,
    port: Int,
    state: String,
    nonce: String,
    challenge: String,
): String {
    val params = linkedMapOf(
        "client_id" to clientId,
        "response_type" to "code",
        "redirect_uri" to "http://127.0.0.1:$port$CHATGPT_CALLBACK_PATH",
        "scope" to CHATGPT_SCOPE,
        "resource" to CHATGPT_RESOURCE,
        "state" to state,
        "nonce" to nonce,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "ext_agent_host_id" to hostId,
    )
    if (clientId == CHATGPT_DYNAMIC_CLIENT_ID) {
        params["agent_name_hint"] = "Rivet"
    }
    val query = params.entries.joinToString("&") { (k, v) ->
        "${enc(k)}=${enc(v)}"
    }
    return "$CHATGPT_AUTHORIZE_URL?$query"
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

// --- Credential record ----------------------------------------------------

@Serializable
data class ChatGptBundle(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresInSec: Long,
    val savedAtMs: Long,
    val scopes: List<String>,
    val sub: String,
    val email: String?,
) {
    /** True when the access token should be refreshed before use (60s margin). */
    fun accessTokenExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= savedAtMs + (expiresInSec - 60).coerceAtLeast(0) * 1000
}

sealed interface ChatGptSignInResult {
    data class Success(val bundle: ChatGptBundle) : ChatGptSignInResult
    data object UserCancelled : ChatGptSignInResult
    data object PlanNotGranted : ChatGptSignInResult
    /** True transport failure (unreachable host, socket error). Never use for
     * server rejections or timeouts — those get their own results below. */
    data class NetworkError(val message: String) : ChatGptSignInResult
    data object InvalidCallback : ChatGptSignInResult
    /** Refresh token is dead; clear local tokens and re-run OAuth. */
    data object NeedsReauth : ChatGptSignInResult
    /** No callback arrived before the wait expired (user abandoned the flow).
     * Not a network failure — never show the "check your connection" copy. */
    data object TimedOut : ChatGptSignInResult
    /** The token endpoint answered with an explicit error code (e.g.
     * invalid_grant). Surface the code; it is a server rejection, not a
     * network failure. */
    data class ServerRejected(val code: String) : ChatGptSignInResult
}

// --- Token endpoint -------------------------------------------------------

private suspend fun postTokenForm(
    form: FormBody,
    requireIdToken: Boolean = true,
): TokenResult {
    val request = Request.Builder()
        .url(CHATGPT_TOKEN_URL)
        .post(form)
        .header("Accept", "application/json")
        .build()
    val response = try {
        http.quick().await(request)
    } catch (e: Exception) {
        return TokenResult.NetworkFailed
    }
    response.use {
        val body = try {
            it.readBoundedBody()
        } catch (e: Exception) {
            null
        } ?: return TokenResult.Failed("invalid_response")
        return parseTokenResponse(body, requireIdToken = requireIdToken)
    }
}

/**
 * Exchanges an authorization code for tokens. [clientId] is the ISSUED id from
 * the callback; [redirectUri] must be byte-identical to the authorize call.
 */
suspend fun exchangeCode(
    code: String,
    verifier: String,
    redirectUri: String,
    clientId: String,
): TokenResult = postTokenForm(
    FormBody.Builder()
        .add("grant_type", "authorization_code")
        .add("code", code)
        .add("code_verifier", verifier)
        .add("redirect_uri", redirectUri)
        .add("client_id", clientId)
        .add("resource", CHATGPT_RESOURCE)
        .build(),
    requireIdToken = true,
)

/**
 * Refreshes tokens. [clientId] is the issued id, never "dynamic_agent_client".
 * `scope` is deliberately omitted (spec). The returned refresh token REPLACES
 * the old one — the caller must persist it.
 */
suspend fun refreshTokens(clientId: String, refreshToken: String): TokenResult =
    postTokenForm(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("resource", CHATGPT_RESOURCE)
            .build(),
        requireIdToken = false,
    )

/** True when the granted scope string includes plan usage. */
fun hasPlanScope(grantedScope: String): Boolean =
    grantedScope.split(' ').any { it == CHATGPT_REQUIRED_SCOPE }

// --- ID-token validation --------------------------------------------------

/**
 * Fetches the JWKS JSON from the SIWC OpenID configuration. Null on any failure.
 */
suspend fun fetchJwks(): String? {
    val configBody = try {
        val req = requestBuilder(CHATGPT_OPENID_CONFIG_URL).get().build()
        http.quick().await(req).use { if (it.isSuccessful) it.readBoundedBody() else null }
    } catch (e: Exception) {
        null
    } ?: return null
    val jwksUri = parseJsonObject(configBody)?.get("jwks_uri")?.str() ?: return null
    if (!ChatGptIdTokenValidator.isPinnedHttpsEndpoint(jwksUri)) return null
    return try {
        val req = requestBuilder(jwksUri).get().build()
        http.quick().await(req).use { if (it.isSuccessful) it.readBoundedBody() else null }
    } catch (e: Exception) {
        null
    }
}

/**
 * Validates an ID token per the SIWC spec: RS256 signature against the JWKS,
 * iss/aud/exp/nonce checks. Returns the `sub` (account identity) on success,
 * null on any failure. [nowMs] is injectable for tests.
 */
fun validateIdToken(
    idToken: String,
    expectedClientId: String,
    expectedNonce: String,
    jwksJson: String,
    nowMs: Long = System.currentTimeMillis(),
): String? = ChatGptIdTokenValidator.validateIdToken(
    idToken = idToken,
    expectedIssuer = CHATGPT_ISSUER,
    expectedClientId = expectedClientId,
    expectedNonce = expectedNonce,
    jwksJson = jwksJson,
    nowMs = nowMs,
)

/** Best-effort revoke of a ChatGPT refresh token. Returns false on any failure. */
suspend fun revokeChatGptRefreshToken(clientId: String, refreshToken: String): Boolean {
    return try {
        val configBody = http.quick().await(requestBuilder(CHATGPT_OPENID_CONFIG_URL).get().build())
            .use { if (it.isSuccessful) it.readBoundedBody() else null } ?: return false
        val endpoint = parseJsonObject(configBody)?.get("revocation_endpoint")?.str() ?: return false
        if (!ChatGptIdTokenValidator.isPinnedHttpsEndpoint(endpoint)) return false
        val request = Request.Builder()
            .url(endpoint)
            .post(
                FormBody.Builder()
                    .add("token", refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", clientId)
                    .build(),
            )
            .header("Accept", "application/json")
            .build()
        http.quick().await(request).use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }
}

/** Best-effort `email` claim from an ID token payload; null when absent. */
fun idTokenEmail(idToken: String): String? =
    ChatGptIdTokenValidator.idTokenEmail(idToken)
