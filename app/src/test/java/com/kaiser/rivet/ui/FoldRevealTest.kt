package com.kaiser.rivet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FoldRevealTest {
    private object ZeroMotionScale : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    @get:Rule
    val compose = createComposeRule(effectContext = ZeroMotionScale)

    @Test
    fun zeroSystemMotionScaleJumpsToTheFullHeight() {
        var expanded by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FoldReveal(expanded = expanded, modifier = Modifier.testTag("fold")) {
                Box(Modifier.fillMaxWidth().height(120.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { expanded = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val first = compose.onNodeWithTag("fold").fetchSemanticsNode().size.height
        compose.mainClock.advanceTimeBy(200)
        val full = compose.onNodeWithTag("fold").fetchSemanticsNode().size.height

        assertTrue("body should have a non-zero height", full > 0)
        assertEquals("fold is open on the first frame", full, first)
    }
}
