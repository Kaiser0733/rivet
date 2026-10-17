package com.kaiser.rivet.storage

import android.content.Context
import java.util.UUID

// ext_agent_host_id for Sign in with ChatGPT: an opaque per-device identifier,
// generated once and reused for every sign-in on this host. Not a secret —
// plain SharedPreferences, no encryption. The SIWC spec requires the
// urn:uuid: form (bare UUIDs are rejected server-side with
// invalid_authorize_request).
class HostIdStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun getOrCreateHostId(): String {
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { stored ->
            // Migration: earlier builds persisted a bare UUID, which OpenAI
            // rejects. Rewrite in place with the urn:uuid: prefix — same UUID,
            // so the per-device identity stays stable across the upgrade.
            if (!stored.startsWith("urn:")) {
                val migrated = "urn:uuid:$stored"
                prefs.edit().putString(KEY, migrated).commit()
                return migrated
            }
            return stored
        }
        val fresh = "urn:uuid:${UUID.randomUUID()}"
        // commit() so a second instance in the same process can never observe
        // an empty slot and mint a competing id.
        prefs.edit().putString(KEY, fresh).commit()
        return prefs.getString(KEY, null) ?: fresh
    }

    // Issued ChatGPT client id (oaiapp_...) from the OAuth callback, persisted
    // the moment it arrives — BEFORE the token exchange. If the exchange fails
    // (e.g. invalid_grant: the code was consumed or rejected), the next
    // authorization reuses this id instead of re-registering as
    // dynamic_agent_client, per the SIWC errors-and-recovery doc ("fresh
    // authorization with retained issued client_id"). Cleared once an exchange
    // succeeds and the bundle takes over. A public client identifier, not a
    // secret — plain prefs like the host id, and never logged.
    fun savePendingClientId(id: String) {
        prefs.edit().putString(KEY_PENDING_CLIENT_ID, id).apply()
    }

    fun pendingClientId(): String? =
        prefs.getString(KEY_PENDING_CLIENT_ID, null)?.takeIf { it.isNotBlank() }

    fun clearPendingClientId() {
        prefs.edit().remove(KEY_PENDING_CLIENT_ID).apply()
    }

    private companion object {
        const val PREFS_NAME = "rivet_host_id"
        const val KEY = "ext_agent_host_id"
        const val KEY_PENDING_CLIENT_ID = "pending_client_id"
    }
}
