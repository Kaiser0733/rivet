package com.kaiser.rivet.provider

import com.kaiser.rivet.storage.SecretStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Single choke point for "valid ChatGPT plan access token". Used by both the
// send-time path (ChatViewModel) and the editor path (ProvidersViewModel) so
// the refresh-serialize-persist logic lives in exactly one place.
//
// Refresh tokens ROTATE: every refresh response carries a replacement. The
// mutex serializes refreshes so two concurrent callers can never race a
// rotating token; the new bundle is persisted BEFORE the token is handed
// out. Transient network failures are surfaced separately from dead refresh
// tokens so offline sends never masquerade as expired sessions.
private val chatGptRefreshMutex = Mutex()

sealed interface ChatGptTokenResult {
    data class Ready(val accessToken: String) : ChatGptTokenResult
    data object ReauthRequired : ChatGptTokenResult
    data object NetworkFailed : ChatGptTokenResult
}

internal fun decodeChatGptBundleRecord(raw: String?): ChatGptBundle? = try {
    raw?.let { chatGptBundleJson.decodeFromString(ChatGptBundle.serializer(), it) }
} catch (_: Exception) {
    null
}

suspend fun resolveChatGptToken(
    secrets: SecretStore,
    config: ProviderConfig,
): ChatGptTokenResult {
    val bundle = decodeChatGptBundleRecord(secrets.oauthBundle(config.id))
        ?: return ChatGptTokenResult.ReauthRequired
    if (!bundle.accessTokenExpired()) return ChatGptTokenResult.Ready(bundle.accessToken)

    return chatGptRefreshMutex.withLock {
        // Re-read: another caller may have refreshed or signed out while we waited.
        val fresh = decodeChatGptBundleRecord(secrets.oauthBundle(config.id))
            ?: return@withLock ChatGptTokenResult.ReauthRequired
        if (!fresh.accessTokenExpired()) return@withLock ChatGptTokenResult.Ready(fresh.accessToken)
        val renewed = when (val result = refreshBundleResult(fresh)) {
            is ChatGptRefreshResult.Renewed -> result.bundle
            ChatGptRefreshResult.ReauthRequired -> return@withLock ChatGptTokenResult.ReauthRequired
            ChatGptRefreshResult.NetworkFailed -> return@withLock ChatGptTokenResult.NetworkFailed
        }
        val encoded = try {
            chatGptBundleJson.encodeToString(ChatGptBundle.serializer(), renewed)
        } catch (_: Exception) {
            return@withLock ChatGptTokenResult.ReauthRequired
        }
        if (!secrets.saveOAuthBundle(config.id, encoded)) return@withLock ChatGptTokenResult.ReauthRequired
        ChatGptTokenResult.Ready(renewed.accessToken)
    }
}

suspend fun resolveChatGptAccessToken(
    secrets: SecretStore,
    config: ProviderConfig,
): String? = (resolveChatGptToken(secrets, config) as? ChatGptTokenResult.Ready)?.accessToken
