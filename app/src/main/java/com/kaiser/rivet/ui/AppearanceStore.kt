package com.kaiser.rivet.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal const val MIN_ROSE_INTENSITY = 70
internal const val MAX_ROSE_INTENSITY = 100
internal const val DEFAULT_ROSE_INTENSITY = MAX_ROSE_INTENSITY

enum class RivetThemeMode { Dark, Light, System }

data class RivetAppearance(
    val themeMode: RivetThemeMode = RivetThemeMode.Dark,
    // Kept so a legacy stored value is never erased; the S1 theme no longer reads it.
    val roseIntensity: Int = DEFAULT_ROSE_INTENSITY,
)

private val Context.appearanceData by preferencesDataStore(name = "appearance")

class AppearanceStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.appearanceData,
) {
    private val intensityKey = intPreferencesKey("rose_intensity")

    private val themeKey = stringPreferencesKey("theme_mode")

    val appearance: Flow<RivetAppearance> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            val storedTheme = preferences.asMap()[themeKey] as? String
            RivetAppearance(
                // "Rose" predates the Dark/Light/System modes and migrates to Light;
                // anything unknown falls back to the Dark default.
                themeMode = when (storedTheme) {
                    "Dark" -> RivetThemeMode.Dark
                    "Light" -> RivetThemeMode.Light
                    "System" -> RivetThemeMode.System
                    "Rose" -> RivetThemeMode.Light
                    else -> RivetThemeMode.Dark
                },
                roseIntensity = clampRoseIntensity(preferences[intensityKey] ?: DEFAULT_ROSE_INTENSITY),
            )
        }

    suspend fun setThemeMode(value: RivetThemeMode) {
        dataStore.edit { it[themeKey] = value.name }
    }

    suspend fun setRoseIntensity(value: Int) {
        dataStore.edit { it[intensityKey] = clampRoseIntensity(value) }
    }
}

internal fun clampRoseIntensity(value: Int): Int = value.coerceIn(MIN_ROSE_INTENSITY, MAX_ROSE_INTENSITY)
