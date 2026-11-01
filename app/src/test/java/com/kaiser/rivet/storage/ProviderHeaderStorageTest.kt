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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        assertEquals(emptyList<ProviderConfig>(), store().configSnapshot())

        // F29: the stored list is kept exactly as written instead of being replaced by
        // an empty one, and one copy is set aside for a later build to recover.
        assertEquals(payload, dataStore.data.first()[configsKey])
        assertEquals(payload, dataStore.data.first()[stringPreferencesKey("configs_unreadable")])
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
    fun anUnreadableHeaderIsDroppedWithoutLosingTheProviderOrTheCiphertext() = runBlocking {
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

        val damaged = store(SecretStore(context, wrongKey))

        // The provider still loads; only the headers Rivet cannot read are gone.
        assertEquals(config.copy(headers = emptyList()),
            damaged.configSnapshot().single { it.id == id })
        assertEquals(2, damaged.droppedHeaders.value)
        // Nothing is rewritten, so the values stay recoverable with the right key.
        assertEquals(before.toList(), file.readBytes().toList())
        assertEquals(config, original.configSnapshot().single { it.id == id })
        assertEquals(0, original.droppedHeaders.value)
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
    fun corruptedTaggedHeaderIsSkippedAndLeavesStoredBytesUntouched() = runBlocking {
        val config = config(listOf(ProviderHeader("X-Bad", "rivet-encrypted:v1:broken")))
        raw(listOf(config))
        val before = file.readBytes()
        val store = store()

        val loaded = store.configSnapshot().single()

        assertEquals(emptyList<ProviderHeader>(), loaded.headers)
        assertEquals(1, store.droppedHeaders.value)
        assertEquals(before.toList(), file.readBytes().toList())
    }

    @Test
    fun aBrokenHeaderDoesNotBlockMigrationOfTheGoodOne() = runBlocking {
        val config = config(listOf(
            ProviderHeader("X-Legacy", "still-plaintext"),
            ProviderHeader("X-Bad", "rivet-encrypted:v1:broken"),
        ))
        raw(listOf(config))

        val loaded = store().configSnapshot().single()

        // The readable header survives and its plaintext no longer sits on disk;
        // the unreadable one stays encrypted and is skipped.
        assertEquals(listOf(ProviderHeader("X-Legacy", "still-plaintext")), loaded.headers)
        val stored = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(stored.contains("still-plaintext"))
        assertTrue(stored.contains("rivet-encrypted:v1:broken"))
    }

    // A store this build cannot read must never be replaced by the single item that
    // happens to be saving at the time.
    @Test
    fun savingDoesNotOverwriteAStoreItCannotRead() = runBlocking {
        val payload = """{"configs":"not-a-list"}"""
        dataStore.edit { it[configsKey] = payload }

        val threw = try { store().save(config(emptyList())); false } catch (_: Exception) { true }
        val deleteThrew = try { store().delete("anything"); false } catch (_: Exception) { true }

        assertTrue(threw)
        assertTrue(deleteThrew)
        assertEquals(payload, dataStore.data.first()[configsKey])
        assertTrue(String(file.readBytes(), Charsets.ISO_8859_1).contains("not-a-list"))
    }

    @Test
    fun anUnreadableStoreIsKeptUnderItsOwnKey() = runBlocking {
        val payload = """{"configs":"not-a-list"}"""
        dataStore.edit { it[configsKey] = payload }

        assertEquals(emptyList<ProviderConfig>(), store().configSnapshot())

        val kept = dataStore.data.first()[stringPreferencesKey("configs_unreadable")]
        assertEquals(payload, kept)
        assertEquals(payload, dataStore.data.first()[configsKey])
    }

    // A keystore Rivet cannot reach is not a reason to take the app down.
    @Test
    fun aFailingKeystoreDegradesEveryEntryPoint() = runBlocking {
        val id = "failing-${UUID.randomUUID()}"
        val working = SecretStore(context, key)
        assertTrue(working.saveApiKey(id, "secret"))
        assertTrue(working.saveOAuthBundle(id, """{"a":1}"""))
        val tagged = working.encryptProviderHeader(id, 0, "header-value")!!
        val broken = SecretStore(context) { throw IllegalStateException("keystore unavailable") }

        assertFalse(broken.saveApiKey("$id-2", "secret"))
        assertNull(broken.apiKey(id))
        assertNull(broken.encryptProviderHeader(id, 0, "value"))
        assertNull(broken.decryptProviderHeader(id, 0, tagged))
        assertFalse(broken.saveOAuthBundle("$id-2", """{"a":2}"""))
        assertNull(broken.oauthBundle(id))
        // The stored values are untouched by the failed attempts.
        assertEquals("secret", working.apiKey(id))
        assertEquals("""{"a":1}""", working.oauthBundle(id))
    }
}
