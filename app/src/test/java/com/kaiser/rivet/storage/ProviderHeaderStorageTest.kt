package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderHeader
import com.kaiser.rivet.provider.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class ProviderHeaderStorageTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val configsKey = stringPreferencesKey("configs")
    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var scope: CoroutineScope

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        file = File(context.cacheDir, "providers-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After fun cleanup() {
        scope.cancel()
        file.delete()
    }

    private fun store(secret: SecretStore = SecretStore(context, key)) =
        ProviderStore(context, secret, dataStore)

    private fun config(headers: List<ProviderHeader>) = ProviderConfig(
        id = UUID.randomUUID().toString(),
        type = ProviderType.OpenAiCompatible,
        name = "Test provider",
        baseUrl = "https://example.invalid/v1",
        model = "test-model",
        headers = headers,
    )

    private suspend fun raw(configs: List<ProviderConfig>) {
        dataStore.edit { prefs ->
            prefs[configsKey] = Json.encodeToString(ListSerializer(ProviderConfig.serializer()), configs)
        }
    }

    @Test
    fun unknownStoredProviderTypeDegradesToEmptyWithoutRewritingHistory() = runBlocking {
        val payload = """[{"id":"x","type":"future_thing","name":"X","baseUrl":"https://x","model":"m"}]"""
        dataStore.edit { it[configsKey] = payload }
        val before = file.readBytes()

        assertEquals(emptyList<ProviderConfig>(), store().configSnapshot())
        assertEquals(before.toList(), file.readBytes().toList())
    }

    @Test
    fun customHeaderValueIsEncryptedAtRestAndRestoredForRequests() = runBlocking {
        val id = UUID.randomUUID().toString()
        val secret = "header-secret-${UUID.randomUUID()}"
        val store = store()
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
        assertEquals(config, store().configSnapshot().single { it.id == id })

        val data = file.readBytes()
        val stored = String(data, Charsets.ISO_8859_1)
        assertFalse(stored.contains(secret))
        store.configSnapshot()
        assertEquals(data.toList(), file.readBytes().toList())
    }

    @Test
    fun wrongKeyCannotRewriteTaggedHeadersAsPlaintext() = runBlocking {
        val id = UUID.randomUUID().toString()
        val wrongKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val original = store()
        val config = ProviderConfig(
            id = id,
            type = ProviderType.OpenAiCompatible,
            name = "Test provider",
            baseUrl = "https://example.invalid/v1",
            model = "test-model",
            headers = listOf(
                ProviderHeader("X-First", "first-secret"),
                ProviderHeader("X-Second", "second-secret"),
            ),
        )
        original.save(config)
        val before = file.readBytes()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store(SecretStore(context, wrongKey)).configSnapshot() }
        }

        assertEquals(before.toList(), file.readBytes().toList())
        assertEquals(config, original.configSnapshot().single { it.id == id })
    }

    @Test
    fun legacyPlaintextHeadersMigrateTogetherOnlyOnce() = runBlocking {
        val config = config(listOf(
            ProviderHeader("X-One", "legacy-one"),
            ProviderHeader("X-Two", "legacy-two"),
        ))
        raw(listOf(config))
        val store = store()

        assertEquals(config, store.configSnapshot().single())
        val migrated = file.readBytes()
        assertFalse(String(migrated, Charsets.ISO_8859_1).contains("legacy-one"))
        assertFalse(String(migrated, Charsets.ISO_8859_1).contains("legacy-two"))
        assertEquals(config, store.configSnapshot().single())
        assertEquals(migrated.toList(), file.readBytes().toList())
    }

    @Test
    fun corruptedTaggedHeaderLeavesPreviouslyStoredBytesUntouched() = runBlocking {
        val config = config(listOf(ProviderHeader("X-Bad", "rivet-encrypted:v1:broken")))
        raw(listOf(config))
        val before = file.readBytes()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store().configSnapshot() }
        }
        assertEquals(before.toList(), file.readBytes().toList())
    }

    @Test
    fun partiallyCorruptedHeaderListDoesNotPartiallyMigrate() = runBlocking {
        val config = config(listOf(
            ProviderHeader("X-Legacy", "still-plaintext"),
            ProviderHeader("X-Bad", "rivet-encrypted:v1:broken"),
        ))
        raw(listOf(config))
        val before = file.readBytes()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store().configSnapshot() }
        }
        assertEquals(before.toList(), file.readBytes().toList())
    }
}
