package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStartGateTest {
    @Test fun createdEngineIsRejectedWhenClosedOrCancelledWhileInFlight() {
        assertFalse(
            TerminalStartGate.canAcceptCreatedEngine(
                ticket = 1L,
                currentGeneration = 2L,
                finished = true,
                requested = false,
                jobActive = false,
            ),
        )
        assertFalse(
            TerminalStartGate.canAcceptCreatedEngine(
                ticket = 1L,
                currentGeneration = 1L,
                finished = false,
                requested = true,
                jobActive = false,
            ),
        )
        assertTrue(
            TerminalStartGate.canAcceptCreatedEngine(
                ticket = 1L,
                currentGeneration = 1L,
                finished = false,
                requested = true,
                jobActive = true,
            ),
        )
    }

    @Test fun openWhileStartingResetsOnProjectSwitchDeadCwdOrCancelledJob() {
        assertEquals(
            TerminalOpenReset.CLOSE_ENDED,
            TerminalStartGate.decideOpenReset(
                hasLiveEngine = false,
                isStarting = true,
                liveCwd = "/tmp/wsA",
                cwdExists = true,
                projectIdentity = "B",
                existingIdentity = "A",
                jobCancelled = false,
            ),
        )
        assertEquals(
            TerminalOpenReset.CLOSE_SILENT,
            TerminalStartGate.decideOpenReset(
                hasLiveEngine = false,
                isStarting = true,
                liveCwd = "/tmp/wsA",
                cwdExists = true,
                projectIdentity = "A",
                existingIdentity = "A",
                jobCancelled = true,
            ),
        )
        assertEquals(
            TerminalOpenReset.CLOSE_SILENT,
            TerminalStartGate.decideOpenReset(
                hasLiveEngine = false,
                isStarting = true,
                liveCwd = "/tmp/deleted",
                cwdExists = false,
                projectIdentity = "A",
                existingIdentity = "A",
                jobCancelled = false,
            ),
        )
    }

    @Test fun launchCompletionAndManagedRegistrationRequireActiveJob() {
        assertFalse(
            TerminalStartGate.canCompleteLaunch(
                ticket = 1L,
                currentGeneration = 1L,
                finished = false,
                requested = true,
                jobActive = false,
            ),
        )
        assertFalse(
            TerminalStartGate.canCompleteLaunch(
                ticket = 1L,
                currentGeneration = 1L,
                finished = true,
                requested = true,
                jobActive = true,
            ),
        )
        assertFalse(TerminalStartGate.canAcceptManagedRegistration(jobActive = false))
        assertTrue(TerminalStartGate.canAcceptManagedRegistration(jobActive = true))
    }
}
