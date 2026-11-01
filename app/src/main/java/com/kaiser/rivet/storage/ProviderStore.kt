package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.providerData: DataStore<Preferences> by preferencesDataStore("providers")

// Provider configuration uses one JSON list under a stable key. Header
// values are encrypted before they enter DataStore; API keys stay separate.
class ProviderStore(
    context: Context,
    private val secrets: SecretStore = SecretStore(context),
    private val dataStore: DataStore<Preferences> = context.providerData,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Stable keys: renaming either of these silently orphans stored
    // configuration on every installed copy.
    private val configsKey = stringPreferencesKey("configs")
    private val activeKey = stringPreferencesKey("active_id")

    val configs: Flow<List<ProviderConfig>> = flow {
        migrateLegacyHeaderValues()
        emitAll(dataStore.data.map { prefs ->
            val (configs, lost) = decodeForUse(prefs[configsKey])
            dropped.value = lost
            configs
        }.flowOn(Dispatchers.IO))
    }

    val activeId: Flow<String?> = dataStore.data.map { it[activeKey] }

    private fun decode(raw: String?): List<ProviderConfig> = try {
        if (raw == null) emptyList()
        else json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
    } catch (e: Exception) {
        emptyList() // unreadable store degrades to empty, never crashes the shell
    }

    // One unreadable custom header must not hide every configured provider, so it is
    // dropped and counted instead. The count lets the UI say plainly what happened.
    private val dropped = MutableStateFlow(0)
    val droppedHeaders: StateFlow<Int> = dropped.asStateFlow()

    suspend fun save(config: ProviderConfig) {
        migrateLegacyHeaderValues()
        val protected = withContext(Dispatchers.IO) { protectHeaders(config) }
        dataStore.edit { prefs ->
            val current = decode(prefs[configsKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == protected.id }
            if (idx >= 0) current[idx] = protected else current.add(protected)
            prefs[configsKey] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), current)
        }
    }

    suspend fun delete(id: String) {
        migrateLegacyHeaderValues()
        dataStore.edit { prefs ->
            val remaining = decode(prefs[configsKey]).filterNot { it.id == id }
            prefs[configsKey] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), remaining)
        }
        if (activeId.first() == id) setActive(null)
        secrets.clearApiKey(id)
        secrets.clearOAuthBundle(id)
    }

    suspend fun setActive(id: String?) {
        dataStore.edit {
            if (id == null) it.remove(activeKey) else it[activeKey] = id
        }
    }

    // One-shot reads for send-time snapshots; avoids collecting a Flow just
    // to read it once.
    suspend fun activeIdSnapshot(): String? = activeId.first()

    suspend fun configSnapshot(): List<ProviderConfig> = configs.first()

    private suspend fun migrateLegacyHeaderValues() {
        dataStore.edit { prefs ->
            val current = decode(prefs[configsKey])
            var rewrite = false
            val migrated = current.map { config -> config.copy(
                headers = config.headers.mapIndexedNotNull { index, header ->
                    if (secrets.isTaggedProviderHeader(header.value)) {
                        // Kept even when it will not decrypt: the ciphertext is harmless
                        // at rest and stays recoverable if the key comes back.
                        header
                    } else {
                        val encrypted = secrets.encryptProviderHeader(config.id, index, header.value)
                        rewrite = true
                        // A plaintext value that cannot be encrypted is removed rather
                        // than left readable on disk.
                        if (encrypted != null) header.copy(value = encrypted) else null
                    }
                },
            ) }
            if (rewrite) {
                prefs[configsKey] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), migrated)
            }
        }
    }

    private fun protectHeaders(config: ProviderConfig): ProviderConfig = config.copy(
        headers = config.headers.mapIndexed { index, header ->
            header.copy(value = secrets.encryptProviderHeader(config.id, index, header.value)
                ?: throw IllegalStateException("provider header encryption failed"))
        },
    )

    private fun decodeForUse(raw: String?): Pair<List<ProviderConfig>, Int> {
        var lost = 0
        val configs = decode(raw).map { config ->
            config.copy(headers = config.headers.mapIndexedNotNull { index, header ->
                secrets.decryptProviderHeader(config.id, index, header.value)
                    ?.let { ProviderHeader(header.name, it) }
                    ?: run { lost++; null }
            })
        }
        return configs to lost
    }
}
