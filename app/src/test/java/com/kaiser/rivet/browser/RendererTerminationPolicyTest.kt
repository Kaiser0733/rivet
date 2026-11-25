package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun registryTerminatesOnlyProcessesOwnedByTheDepartingView() {
        val registry = RendererProcessRegistry()
        val departing = Any()
        val replacement = Any()
        val oldProcess = Any()
        val shared = Any()
        var oldTerminations = 0
        var sharedTerminations = 0

        registry.record(oldProcess, ownerView = departing) { oldTerminations++; true }
        registry.record(oldProcess, ownerView = departing) { oldTerminations++; true }
        registry.record(shared, ownerView = departing) { sharedTerminations++; true }
        registry.record(shared, ownerView = replacement) { sharedTerminations++; true }

        assertEquals(1, registry.terminateForView(departing, supported = true))
        assertEquals(1, oldTerminations)
        assertEquals(0, sharedTerminations)
        assertEquals(1, registry.terminateForView(replacement, supported = true))
        assertEquals(1, sharedTerminations)
        assertEquals(0, registry.liveCount())
    }
}
