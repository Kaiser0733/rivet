package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.rivet.provider.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.providerData: DataStore<Preferences> by preferencesDataStore("providers")

// Non-secret provider configuration. One JSON list under one stable key;
// each entry's `id` is its permanent identity. API keys never pass through
// this store — deleting a provider also drops its key from SecretStore.
class ProviderStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    // Stable keys: renaming either of these silently orphans stored
    // configuration on every installed copy.
    private val configsKey = stringPreferencesKey("configs")
    private val activeKey = stringPreferencesKey("active_id")

    val configs: Flow<List<ProviderConfig>> = context.providerData.data.map { prefs ->
        decode(prefs[configsKey])
    }

    val activeId: Flow<String?> = context.providerData.data.map { it[activeKey] }

    private fun decode(raw: String?): List<ProviderConfig> = try {
        if (raw == null) emptyList()
        else json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
    } catch (e: Exception) {
        emptyList() // unreadable store degrades to empty, never crashes the shell
    }

    suspend fun save(config: ProviderConfig) {
        context.providerData.edit { prefs ->
            val current = decode(prefs[configsKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == config.id }
            if (idx >= 0) current[idx] = config else current.add(config)
            prefs[configsKey] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), current)
        }
    }

    suspend fun delete(id: String) {
        context.providerData.edit { prefs ->
            val remaining = decode(prefs[configsKey]).filterNot { it.id == id }
            prefs[configsKey] = json.encodeToString(ListSerializer(ProviderConfig.serializer()), remaining)
        }
        if (activeId.first() == id) setActive(null)
        SecretStore(context).clearApiKey(id)
    }

    suspend fun setActive(id: String?) {
        context.providerData.edit {
            if (id == null) it.remove(activeKey) else it[activeKey] = id
        }
    }

    // One-shot reads for send-time snapshots; avoids collecting a Flow just
    // to read it once.
    suspend fun activeIdSnapshot(): String? = activeId.first()

    suspend fun configSnapshot(): List<ProviderConfig> = configs.first()
}
