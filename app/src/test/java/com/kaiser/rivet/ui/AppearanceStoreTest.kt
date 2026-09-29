package com.kaiser.rivet.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    @Test fun defaultsToOriginalAndPersistsOnlyBoundedValues() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "appearance-${UUID.randomUUID()}.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
        val first = AppearanceStore(app, firstDataStore)

        assertEquals(DEFAULT_ROSE_INTENSITY, first.roseIntensity.first())
        first.setRoseIntensity(Int.MIN_VALUE)
        assertEquals(MIN_ROSE_INTENSITY, first.roseIntensity.first())
        first.setRoseIntensity(Int.MAX_VALUE)
        assertEquals(MAX_ROSE_INTENSITY, first.roseIntensity.first())
        first.setRoseIntensity(82)
        firstScope.cancel()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondDataStore = PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file })
        val reopened = AppearanceStore(app, secondDataStore)
        assertEquals(82, reopened.roseIntensity.first())
        secondScope.cancel()
    }

    @Test fun rosePaletteKeepsOriginalAtDefaultAndMutesOnlyRoseColors() {
        val original = Color(0xFFE6BBC3)
        val full = roseSurfaceColor(original, DEFAULT_ROSE_INTENSITY)
        val dimmed = roseSurfaceColor(original, MIN_ROSE_INTENSITY)

        assertEquals(original, full)
        assertEquals(1f, full.alpha, 0f)
        assertEquals(1f, dimmed.alpha, 0f)
        assertNotEquals(full, dimmed)
        assertEquals(rivetColors(DEFAULT_ROSE_INTENSITY).onBackground,
            rivetColors(MIN_ROSE_INTENSITY).onBackground)
        assertEquals(rivetColors(DEFAULT_ROSE_INTENSITY).primary,
            rivetColors(MIN_ROSE_INTENSITY).primary)
        assertEquals(MIN_ROSE_INTENSITY, clampRoseIntensity(Int.MIN_VALUE))
        assertEquals(MAX_ROSE_INTENSITY, clampRoseIntensity(Int.MAX_VALUE))
    }

    @Test fun roseIntensityKeepsBodyTextContrastAcrossItsRange() {
        for (intensity in MIN_ROSE_INTENSITY..MAX_ROSE_INTENSITY) {
            val palette = rivetColors(intensity)
            assertTrue(contrast(palette.onBackground, palette.background) >= 4.5)
            assertTrue(contrast(palette.onSurface, palette.surface) >= 4.5)
            assertTrue(contrast(palette.onSurfaceVariant, palette.surfaceVariant) >= 4.5)
            assertTrue(contrast(palette.onErrorContainer, palette.errorContainer) >= 4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val one = luminance(first)
        val two = luminance(second)
        val brighter = maxOf(one, two)
        val darker = minOf(one, two)
        return (brighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val srgb = value.toDouble()
            return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
