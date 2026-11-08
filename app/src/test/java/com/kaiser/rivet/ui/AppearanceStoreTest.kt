package com.kaiser.rivet.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AppearanceStoreTest {
    @Test fun defaultsToDarkAndPersistsOnlyBoundedIntensity() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "appearance-${UUID.randomUUID()}.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
        val first = AppearanceStore(app, firstDataStore)

        assertEquals(RivetThemeMode.Dark, first.appearance.first().themeMode)
        assertEquals(DEFAULT_ROSE_INTENSITY, first.appearance.first().roseIntensity)
        first.setRoseIntensity(Int.MIN_VALUE)
        assertEquals(MIN_ROSE_INTENSITY, first.appearance.first().roseIntensity)
        first.setRoseIntensity(Int.MAX_VALUE)
        assertEquals(MAX_ROSE_INTENSITY, first.appearance.first().roseIntensity)
        first.setRoseIntensity(82)
        // Join, not just cancel: DataStore releases the file when its scope finishes, and
        // the second store below opens the same file.
        firstScope.coroutineContext[Job]!!.cancelAndJoin()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondDataStore = PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file })
        val reopened = AppearanceStore(app, secondDataStore)
        assertEquals(82, reopened.appearance.first().roseIntensity)
        secondScope.cancel()
    }

    @Test fun intensityClampBoundsStayAsTheLegacyStoreDefinesThem() {
        assertEquals(MIN_ROSE_INTENSITY, clampRoseIntensity(Int.MIN_VALUE))
        assertEquals(MAX_ROSE_INTENSITY, clampRoseIntensity(Int.MAX_VALUE))
    }

    @Test fun themeDefaultsToDarkAndRejectsUnknownStoredValues() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "appearance-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val store = AppearanceStore(app, dataStore)
            assertEquals(RivetAppearance(), store.appearance.first())
            for (invalid in listOf("", "unknown", "dark", "Dark ", "Rose ")) {
                dataStore.edit { it[stringPreferencesKey("theme_mode")] = invalid }
                assertEquals(RivetThemeMode.Dark, store.appearance.first().themeMode)
            }
            dataStore.edit { it[intPreferencesKey("theme_mode")] = 1 }
            assertEquals(RivetThemeMode.Dark, store.appearance.first().themeMode)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun legacyRoseValueMigratesToLightWithoutErasingRoseIntensity() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "appearance-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            dataStore.edit {
                it[stringPreferencesKey("theme_mode")] = "Rose"
                it[intPreferencesKey("rose_intensity")] = 82
            }
            val store = AppearanceStore(app, dataStore)
            assertEquals(RivetAppearance(RivetThemeMode.Light, 82), store.appearance.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun themeRoundTripSurvivesReopeningWithoutErasingRoseIntensity() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "appearance-${UUID.randomUUID()}.preferences_pb")
        suspend fun reopen(block: suspend (AppearanceStore) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                block(AppearanceStore(app, PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })))
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        reopen { store ->
            store.setRoseIntensity(82)
            store.setThemeMode(RivetThemeMode.System)
            assertEquals(RivetAppearance(RivetThemeMode.System, 82), store.appearance.first())
        }
        reopen { store ->
            assertEquals(RivetAppearance(RivetThemeMode.System, 82), store.appearance.first())
            store.setThemeMode(RivetThemeMode.Light)
        }
        reopen { store -> assertEquals(RivetAppearance(RivetThemeMode.Light, 82), store.appearance.first()) }
    }

    @Test fun resolvedPalettesIgnoreIntensityAndKeepReadableText() {
        for (palette in listOf(
            rivetColors(RivetThemeMode.Dark, true),
            rivetColors(RivetThemeMode.System, true),
            rivetColors(RivetThemeMode.System, false),
            rivetColors(RivetThemeMode.Light, true),
            rivetColors(RivetThemeMode.Light, false),
        )) {
            assertTrue(contrast(palette.onBackground, palette.background) >= 4.5)
            assertTrue(contrast(palette.onSurface, palette.surface) >= 4.5)
            assertTrue(contrast(palette.onSurfaceVariant, palette.surfaceVariant) >= 4.5)
            assertTrue(contrast(palette.onErrorContainer, palette.errorContainer) >= 4.5)
            assertTrue(contrast(palette.onPrimary, palette.primary) >= 4.5)
        }
        // System mode follows the platform, not the stored intensity.
        assertEquals(rivetColors(RivetThemeMode.Dark, true), rivetColors(RivetThemeMode.System, true))
        assertEquals(rivetColors(RivetThemeMode.Light, false), rivetColors(RivetThemeMode.System, false))
    }

    private fun contrast(first: androidx.compose.ui.graphics.Color, second: androidx.compose.ui.graphics.Color): Double {
        val one = luminance(first)
        val two = luminance(second)
        val brighter = maxOf(one, two)
        val darker = minOf(one, two)
        return (brighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: androidx.compose.ui.graphics.Color): Double {
        fun channel(value: Float): Double {
            val srgb = value.toDouble()
            return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
