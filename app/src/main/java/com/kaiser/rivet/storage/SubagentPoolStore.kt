package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.subagent.PoolEntry
import com.kaiser.rivet.subagent.SubagentPoolState
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json

private val Context.subagentPoolData: DataStore<Preferences> by preferencesDataStore("subagents")

/**
 * Persists the user's subagent allow-list. The stored state is deliberately not
 * rewritten when a provider disappears: stale rows are dropped at read time, so
 * deleting and re-adding a provider cannot resurrect a stale model.
 */
class SubagentPoolStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val configs: Flow<List<ProviderConfig>>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stateKey = stringPreferencesKey("state")

    val state: Flow<SubagentPoolState> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .combine(configs.catch { emit(emptyList<ProviderConfig>()) }) { preferences, currentConfigs ->
            filterStale(decode(preferences[stateKey]), currentConfigs)
        }
        .distinctUntilChanged()

    suspend fun save(state: SubagentPoolState) {
        dataStore.edit { it[stateKey] = json.encodeToString(SubagentPoolState.serializer(), state) }
    }

    private fun decode(raw: String?): SubagentPoolState = if (raw == null) {
        SubagentPoolState()
    } else try {
        json.decodeFromString(SubagentPoolState.serializer(), raw)
    } catch (_: Exception) {
        SubagentPoolState()
    }

    constructor(
        context: Context,
        providerStore: ProviderStore = ProviderStore(context),
        dataStore: DataStore<Preferences> = context.subagentPoolData,
    ) : this(dataStore, providerStore.configs)
}

/** Drops rows whose provider config is gone or whose model changed. */
internal fun filterStale(
    state: SubagentPoolState,
    configs: List<ProviderConfig>,
): SubagentPoolState {
    val byId = configs.associateBy { it.id }
    fun live(entry: PoolEntry): Boolean = byId[entry.configId]?.model == entry.model
    return state.copy(
        entries = state.entries.filter(::live).distinct(),
        fixed = state.fixed?.takeIf(::live),
    )
}
