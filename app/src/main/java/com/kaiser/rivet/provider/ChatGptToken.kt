package com.kaiser.rivet.provider

import com.kaiser.rivet.storage.SecretStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

// Single choke point for "valid ChatGPT plan access token". Used by both the
// send-time path (ChatViewModel) and the editor path (ProvidersViewModel) so
// the refresh-serialize-persist logic lives in exactly one place.
//
// Refresh tokens ROTATE: every refresh response carries a replacement. The
// mutex serializes refreshes so two concurrent callers can never race a
// rotating token; the new bundle is persisted BEFORE the token is handed
// out. Returns null when reauth is required (no bundle, malformed bundle,
// dead refresh, or persist failure) — the caller surfaces a sign-in-again
// message. A null from refreshBundle() covers both transient network failure
// and dead refresh token; we deliberately do not clear the stored bundle
// here because we cannot distinguish the two.
private val chatGptRefreshMutex = Mutex()

private val bundleJson = Json { ignoreUnknownKeys = true }

suspend fun resolveChatGptAccessToken(
    secrets: SecretStore,
    config: ProviderConfig,
): String? {
    fun decode(raw: String?): ChatGptBundle? = try {
        raw?.let { bundleJson.decodeFromString(ChatGptBundle.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    val bundle = decode(secrets.oauthBundle(config.id)) ?: return null
    if (!bundle.accessTokenExpired()) return bundle.accessToken

    return chatGptRefreshMutex.withLock {
        // Re-read: another caller may have refreshed while we waited.
        val fresh = decode(secrets.oauthBundle(config.id)) ?: bundle
        if (!fresh.accessTokenExpired()) return@withLock fresh.accessToken
        val renewed = refreshBundle(fresh) ?: return@withLock null
        val encoded = try {
            bundleJson.encodeToString(ChatGptBundle.serializer(), renewed)
        } catch (e: Exception) {
            return@withLock null
        }
        if (!secrets.saveOAuthBundle(config.id, encoded)) return@withLock null
        renewed.accessToken
    }
}
