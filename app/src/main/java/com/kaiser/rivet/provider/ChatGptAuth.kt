package com.kaiser.rivet.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
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

// Error codes from the SIWC errors-and-recovery doc that mean the refresh
// token is dead: clear local tokens and re-run OAuth with the saved client id.
internal val CHATGPT_DEAD_REFRESH_ERRORS = setOf(
    "invalid_grant",
    "invalid_refresh_token",
    "token_expired",
    "refresh_token_expired",
    "refresh_token_invalidated",
    "refresh_token_reused",
)

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

sealed interface TokenResult {
    data class Ok(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val expiresInSec: Long,
        val scope: String,
    ) : TokenResult
    data class Failed(val error: String) : TokenResult
    data object NetworkFailed : TokenResult
}

// --- Token endpoint -------------------------------------------------------

/** Parses a token-endpoint JSON body into a TokenResult. Pure; unit-testable. */
internal fun parseTokenResponse(body: String): TokenResult {
    val obj = parseJsonObject(body) ?: return TokenResult.Failed("invalid_response")
    val error = obj["error"]?.str()
    if (error != null) return TokenResult.Failed(error)
    val access = obj["access_token"]?.str()
    val refresh = obj["refresh_token"]?.str()
    val id = obj["id_token"]?.str()
    if (access == null || refresh == null || id == null) {
        return TokenResult.Failed("invalid_response")
    }
    val expiresIn = (obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()) ?: 3600L
    return TokenResult.Ok(
        accessToken = access,
        refreshToken = refresh,
        idToken = id,
        expiresInSec = expiresIn,
        scope = obj["scope"]?.str().orEmpty(),
    )
}

private suspend fun postTokenForm(form: FormBody): TokenResult {
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
        return parseTokenResponse(body)
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
        http.quick().await(req).use { it.readBoundedBody() }
    } catch (e: Exception) {
        null
    } ?: return null
    val jwksUri = parseJsonObject(configBody)?.get("jwks_uri")?.str() ?: return null
    return try {
        val req = requestBuilder(jwksUri).get().build()
        http.quick().await(req).use { it.readBoundedBody() }
    } catch (e: Exception) {
        null
    }
}

private fun b64UrlDecode(s: String): ByteArray =
    Base64.getUrlDecoder().decode(s)

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
): String? {
    val parts = idToken.split('.')
    if (parts.size != 3) return null
    val header = try {
        parseJsonObject(String(b64UrlDecode(parts[0]), Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        null
    } ?: return null
    if (header["alg"]?.str() != "RS256") return null
    val kid = header["kid"]?.str() ?: return null

    val jwk = parseJsonObject(jwksJson)?.get("keys")?.let { keys ->
        (keys as? JsonArray)
            ?.mapNotNull { it.obj() }
            ?.firstOrNull { it["kid"]?.str() == kid }
    } ?: return null
    val n = try { b64UrlDecode(jwk["n"]?.str() ?: return null) } catch (e: IllegalArgumentException) { return null }
    val e = try { b64UrlDecode(jwk["e"]?.str() ?: return null) } catch (e: IllegalArgumentException) { return null }

    // RS256 signature over "header.payload".
    val signatureValid = try {
        val keySpec = RSAPublicKeySpec(
            java.math.BigInteger(1, n),
            java.math.BigInteger(1, e),
        )
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(publicKey)
        sig.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        sig.verify(b64UrlDecode(parts[2]))
    } catch (ex: Exception) {
        false
    }
    if (!signatureValid) return null

    val claims = try {
        parseJsonObject(String(b64UrlDecode(parts[1]), Charsets.UTF_8))
    } catch (ex: IllegalArgumentException) {
        null
    } ?: return null
    if (claims["iss"]?.str() != CHATGPT_ISSUER) return null
    val audOk = when (val aud = claims["aud"]) {
        is JsonPrimitive ->
            aud.takeIf { it.isString }?.content == expectedClientId
        is JsonArray ->
            aud.mapNotNull { it.str() }.size == 1 && aud[0].str() == expectedClientId
        else -> false
    }
    if (!audOk) return null
    val expSec = claims["exp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    if (expSec <= nowMs / 1000 - 60) return null // 60s clock tolerance
    if (claims["nonce"]?.str() != expectedNonce) return null
    return claims["sub"]?.str()
}

/** Best-effort revoke of a ChatGPT refresh token. Returns false on any failure. */
suspend fun revokeChatGptRefreshToken(clientId: String, refreshToken: String): Boolean {
    return try {
        val configBody = http.quick().await(requestBuilder(CHATGPT_OPENID_CONFIG_URL).get().build())
            .use { it.readBoundedBody() } ?: return false
        val endpoint = parseJsonObject(configBody)?.get("revocation_endpoint")?.str() ?: return false
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
fun idTokenEmail(idToken: String): String? {
    val parts = idToken.split('.')
    if (parts.size != 3) return null
    return try {
        parseJsonObject(String(b64UrlDecode(parts[1]), Charsets.UTF_8))
            ?.get("email")?.str()
    } catch (e: IllegalArgumentException) {
        null
    }
}
