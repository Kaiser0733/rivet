package com.kaiser.rivet.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartPolicyTest {
    @Test fun timeoutPreservesCallbackStartedStateAndStopDoesNotResurrect() {
        assertTrue(
            ForegroundStartPolicy.reconcileServiceStarted(
                awaited = null,
                currentServiceStarted = true,
            ),
        )
        assertFalse(
            ForegroundStartPolicy.reconcileServiceStarted(
                awaited = null,
                currentServiceStarted = false,
            ),
        )
        assertFalse(
            ForegroundStartPolicy.reconcileServiceStarted(
                awaited = true,
                currentServiceStarted = false,
            ),
        )
        assertTrue(
            ForegroundStartPolicy.reconcileServiceStarted(
                awaited = true,
                currentServiceStarted = true,
            ),
        )
    }

    @Test fun persistentProcessMustFinishEvenWhenStopThrows() {
        assertTrue(ForegroundStartPolicy.shouldFinishPersistentOnStopError(isPersistent = true, threw = true))
        assertFalse(ForegroundStartPolicy.shouldFinishPersistentOnStopError(isPersistent = false, threw = true))
    }
}
