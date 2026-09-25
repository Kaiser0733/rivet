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
        emitAll(dataStore.data.map { prefs -> decodeForUse(prefs[configsKey]) }
            .flowOn(Dispatchers.IO))
    }

    val activeId: Flow<String?> = dataStore.data.map { it[activeKey] }

    private fun decode(raw: String?): List<ProviderConfig> = try {
        if (raw == null) emptyList()
        else json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
    } catch (e: Exception) {
        emptyList() // unreadable store degrades to empty, never crashes the shell
    }

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
            val migrated = current.map { config -> config.copy(
                headers = config.headers.mapIndexed { index, header ->
                    if (secrets.isTaggedProviderHeader(header.value)) {
                        check(secrets.decryptProviderHeader(config.id, index, header.value) != null) {
                            "provider header decryption failed"
                        }
                        header
                    } else {
                        header.copy(value = secrets.encryptProviderHeader(config.id, index, header.value)
                            ?: throw IllegalStateException("provider header encryption failed"))
                    }
                },
            ) }
            if (migrated != current) {
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

    private fun decodeForUse(raw: String?): List<ProviderConfig> = decode(raw).map { config ->
        config.copy(headers = config.headers.mapIndexed { index, header ->
            val value = secrets.decryptProviderHeader(config.id, index, header.value)
                ?: throw IllegalStateException("provider header decryption failed")
            ProviderHeader(header.name, value)
        })
    }
}
