package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.rivet.agent.AutonomyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.autonomyData by preferencesDataStore("autonomy")

class AutonomyStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.autonomyData,
) {
    private val modeKey = stringPreferencesKey("mode")

    val mode: Flow<AutonomyMode> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> autonomyModeFrom(preferences.asMap()[modeKey]) }

    suspend fun setMode(mode: AutonomyMode) {
        dataStore.edit { it[modeKey] = mode.name }
    }
}

internal fun autonomyModeFrom(value: Any?): AutonomyMode =
    AutonomyMode.entries.firstOrNull { it.name == value } ?: AutonomyMode.Ask
