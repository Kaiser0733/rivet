package com.kaiser.rivet.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal const val MIN_ROSE_INTENSITY = 70
internal const val MAX_ROSE_INTENSITY = 100
internal const val DEFAULT_ROSE_INTENSITY = MAX_ROSE_INTENSITY

private val Context.appearanceData by preferencesDataStore(name = "appearance")

class AppearanceStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.appearanceData,
) {
    private val intensityKey = intPreferencesKey("rose_intensity")

    val roseIntensity: Flow<Int> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { clampRoseIntensity(it[intensityKey] ?: DEFAULT_ROSE_INTENSITY) }

    suspend fun setRoseIntensity(value: Int) {
        dataStore.edit { it[intensityKey] = clampRoseIntensity(value) }
    }
}

internal fun clampRoseIntensity(value: Int): Int = value.coerceIn(MIN_ROSE_INTENSITY, MAX_ROSE_INTENSITY)
