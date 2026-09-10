package com.kaiser.rivet.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// API keys live only here, in EncryptedSharedPreferences backed by an
// AndroidKeyStore AES-256 key. Keys are write-only from the UI's
// perspective: hasKey/isKeyPresent cover "a key exists" without ever
// returning the plaintext, and no log statement ever touches these values.
class SecretStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "rivet_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Corrupt keyset after a backup-restore bricks the store; a fresh
        // store costs re-entering keys, a crash costs the whole screen.
        context.deleteSharedPreferences("rivet_secrets")
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "rivet_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveApiKey(providerId: String, key: String) {
        prefs.edit().putString(providerId, key).apply()
    }

    // Read only where a request is about to be built; never call this from
    // UI code paths that could render or log the result.
    fun apiKey(providerId: String): String? = prefs.getString(providerId, null)

    fun hasApiKey(providerId: String): Boolean = prefs.contains(providerId)

    fun clearApiKey(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }
}
