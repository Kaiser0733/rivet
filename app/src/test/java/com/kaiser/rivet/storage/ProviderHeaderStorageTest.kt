package com.kaiser.rivet.storage

import android.content.Context
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderHeader
import com.kaiser.rivet.provider.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class ProviderHeaderStorageTest {
    @Test
    fun customHeaderValueIsEncryptedAtRestAndRestoredForRequests() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val id = UUID.randomUUID().toString()
        val secret = "header-secret-${UUID.randomUUID()}"
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = ProviderStore(context, SecretStore(context, key))
        val config = ProviderConfig(
            id = id,
            type = ProviderType.OpenAiCompatible,
            name = "Test provider",
            baseUrl = "https://example.invalid/v1",
            model = "test-model",
            headers = listOf(ProviderHeader("X-Access-Token", secret)),
        )

        store.save(config)
        assertEquals(config, store.configSnapshot().single { it.id == id })
        assertEquals(config, ProviderStore(context, SecretStore(context, key)).configSnapshot().single { it.id == id })

        val data = File(context.filesDir, "datastore/providers.preferences_pb").readBytes()
        val stored = String(data, Charsets.ISO_8859_1)
        assertFalse(stored.contains(secret))
    }
}
