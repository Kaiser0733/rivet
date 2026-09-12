package com.kaiser.rivet.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun saveApiKey(providerId: String, apiKey: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(providerId, encodeSecretRecord(cipher.iv, ciphertext))
            .commit()
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: IOException) {
        false
    }

    // Plaintext leaves this boundary only while constructing a provider
    // request. It is never copied into UI state or persistent configuration.
    fun apiKey(providerId: String): String? {
        val stored = prefs.getString(providerId, null) ?: return null
        val record = decodeSecretRecord(stored) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(GCM_TAG_BITS, record.iv),
            )
            cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(record.ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    fun hasApiKey(providerId: String): Boolean = apiKey(providerId) != null

    fun clearApiKey(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }

    private fun encryptionKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            return@synchronized it
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "rivet_secret_ciphertext_v1"
        const val KEY_ALIAS = "rivet_api_key_aes_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val keyLock = Any()
    }
}

internal data class SecretRecord(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal fun encodeSecretRecord(iv: ByteArray, ciphertext: ByteArray): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return "v1:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
}

internal fun decodeSecretRecord(encoded: String): SecretRecord? {
    val parts = encoded.split(':', limit = 3)
    if (parts.size != 3 || parts[0] != "v1") return null
    return try {
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(parts[1])
        val ciphertext = decoder.decode(parts[2])
        if (iv.size != 12 || ciphertext.size < 16) null
        else SecretRecord(iv, ciphertext)
    } catch (e: IllegalArgumentException) {
        null
    }
}
