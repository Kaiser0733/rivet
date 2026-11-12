package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RendererTerminationPolicyTest {
    @Test fun aSupportedLiveHandleIsTerminatedOnce() {
        var calls = 0
        val outcome = RendererTerminationPolicy.request(true, true) { calls++; true }
        assertEquals(RendererTerminationOutcome.Requested, outcome)
        assertEquals(1, calls)
    }

    @Test fun theUnsupportedPathNeverCallsTerminate() {
        var calls = 0
        val outcome = RendererTerminationPolicy.request(false, false) { calls++; true }
        assertEquals(RendererTerminationOutcome.Unsupported, outcome)
        assertEquals(0, calls)
    }

    @Test fun aMissingHandleNeverCallsTerminate() {
        var calls = 0
        val outcome = RendererTerminationPolicy.request(true, false) { calls++; true }
        assertEquals(RendererTerminationOutcome.NoHandle, outcome)
        assertEquals(0, calls)
    }

    @Test fun aRefusedTerminationIsReportedAndDoesNotThrow() {
        assertEquals(RendererTerminationOutcome.Refused,
            RendererTerminationPolicy.request(true, true) { false })
    }
    @Test fun theTerminatingPathUsesTheStrictOrderRemoveStopClearTerminateDestroy() {
        assertEquals(
            listOf(
                RendererTeardownStep.RemoveFromParent,
                RendererTeardownStep.StopLoading,
                RendererTeardownStep.ClearHistory,
                RendererTeardownStep.TerminateRenderer,
                RendererTeardownStep.Destroy,
            ),
            RendererTerminationPolicy.teardownSteps(featureSupported = true, handleAvailable = true),
        )
    }

    @Test fun theUnsupportedPathStillUsesTheStrictOrderWithoutTerminate() {
        val steps = RendererTerminationPolicy.teardownSteps(
            featureSupported = false, handleAvailable = true)
        assertEquals(RendererTeardownStep.Destroy, steps.last())
        assertFalse(steps.contains(RendererTeardownStep.TerminateRenderer))
    }

}
