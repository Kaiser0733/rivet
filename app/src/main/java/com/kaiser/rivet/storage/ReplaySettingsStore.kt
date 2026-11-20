package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.rivet.replay.replayWatermarkEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.replayData by preferencesDataStore("replay")

internal class ReplaySettingsStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.replayData,
) {
    private val watermarkKey = booleanPreferencesKey("watermark")

    val watermark: Flow<Boolean> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { replayWatermarkEnabled(it.asMap()[watermarkKey]) }

    suspend fun setWatermark(enabled: Boolean) {
        dataStore.edit { it[watermarkKey] = enabled }
    }
}
