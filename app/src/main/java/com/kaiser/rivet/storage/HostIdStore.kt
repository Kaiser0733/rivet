package com.kaiser.rivet.storage

import android.content.Context
import java.util.UUID

// ext_agent_host_id for Sign in with ChatGPT: an opaque per-device identifier,
// generated once and reused for every sign-in on this host. Not a secret —
// plain SharedPreferences, no encryption. (SIWC spec: dynamic client
// registration is keyed partly on this value.)
class HostIdStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun getOrCreateHostId(): String {
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        // commit() so a second instance in the same process can never observe
        // an empty slot and mint a competing id.
        prefs.edit().putString(KEY, fresh).commit()
        return prefs.getString(KEY, null) ?: fresh
    }

    private companion object {
        const val PREFS_NAME = "rivet_host_id"
        const val KEY = "ext_agent_host_id"
    }
}
