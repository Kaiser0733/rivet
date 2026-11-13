package com.kaiser.rivet.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Shared permissive JSON codec for stored [ChatGptBundle] records. */
internal val chatGptBundleJson = Json { ignoreUnknownKeys = true }

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

sealed interface TokenResult {
    data class Ok(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String?,
        val expiresInSec: Long,
        val scope: String,
    ) : TokenResult

    data class Failed(val error: String) : TokenResult
    data object NetworkFailed : TokenResult
}

enum class ChatGptRefreshFailure {
    REAUTH_REQUIRED,
    TRANSIENT,
}

internal data class RefreshedBundleFields(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresInSec: Long,
    val scopes: List<String>,
    val email: String?,
)

/** Parses a token-endpoint JSON body into a TokenResult. Pure; unit-testable. */
internal fun parseTokenResponse(body: String, requireIdToken: Boolean = true): TokenResult {
    val obj = parseJsonObject(body) ?: return TokenResult.Failed("invalid_response")
    val error = obj["error"]?.str()
    if (error != null) return TokenResult.Failed(error)
    val access = obj["access_token"]?.str()
    val refresh = obj["refresh_token"]?.str()
    val id = obj["id_token"]?.str()?.takeIf { it.isNotBlank() }
    if (access == null || refresh == null || (requireIdToken && id == null)) {
        return TokenResult.Failed("invalid_response")
    }
    val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
    return TokenResult.Ok(
        accessToken = access,
        refreshToken = refresh,
        idToken = id,
        expiresInSec = expiresIn,
        scope = obj["scope"]?.str().orEmpty(),
    )
}

internal fun classifyRefreshFailure(result: TokenResult): ChatGptRefreshFailure = when (result) {
    is TokenResult.Failed ->
        if (result.error in CHATGPT_DEAD_REFRESH_ERRORS) ChatGptRefreshFailure.REAUTH_REQUIRED
        else ChatGptRefreshFailure.TRANSIENT
    TokenResult.NetworkFailed -> ChatGptRefreshFailure.TRANSIENT
    is TokenResult.Ok -> ChatGptRefreshFailure.TRANSIENT
}

internal fun mergeRefreshedBundleFields(
    currentIdToken: String,
    currentScopes: List<String>,
    currentEmail: String?,
    tokens: TokenResult.Ok,
    extractEmail: (String) -> String?,
): RefreshedBundleFields {
    val parsedScopes = tokens.scope.split(' ').filter { it.isNotEmpty() }
    val nextIdToken = tokens.idToken ?: currentIdToken
    val nextEmail = tokens.idToken?.let(extractEmail) ?: currentEmail
    return RefreshedBundleFields(
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        idToken = nextIdToken,
        expiresInSec = tokens.expiresInSec,
        scopes = parsedScopes.ifEmpty { currentScopes },
        email = nextEmail,
    )
}
