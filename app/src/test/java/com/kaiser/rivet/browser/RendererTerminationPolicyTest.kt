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

    @Test fun registryTerminatesPrimaryAndSecondaryProcessesAndDropsOnlySuccessfulOrGone() {
        val registry = RendererProcessRegistry()
        val view1 = Any()
        val view2 = Any()
        var p1Terminated = 0
        var p2Terminated = 0
        var p3Terminated = 0
        val p1 = Any()
        val p2 = Any()
        val p3 = Any()

        registry.record(p1, ownerView = view1) { p1Terminated++; true }
        registry.record(p1, ownerView = view1) { p1Terminated++; true } // dedup
        registry.record(p2, ownerView = view2) { p2Terminated++; false }
        registry.record(p3, ownerView = view1) { p3Terminated++; true }
        assertEquals(3, registry.liveCount())

        // onRenderProcessGone for view2 removes p2 without calling terminate on it.
        registry.onProcessGoneForView(view2)
        assertEquals(2, registry.liveCount())

        assertEquals(2, registry.terminateAll(supported = true))
        assertEquals(1, p1Terminated)
        assertEquals(0, p2Terminated)
        assertEquals(1, p3Terminated)
        assertEquals(0, registry.liveCount())

        // Refused termination stays tracked for a later close retry.
        val p4 = Any()
        var p4Calls = 0
        var allowTerminate = false
        registry.record(p4, ownerView = view1) { p4Calls++; allowTerminate }
        assertEquals(0, registry.terminateAll(supported = true))
        assertEquals(1, p4Calls)
        assertEquals(1, registry.liveCount())
        allowTerminate = true
        assertTrue(registry.terminateAll(supported = true) == 1)
        assertEquals(0, registry.liveCount())
    }
}
