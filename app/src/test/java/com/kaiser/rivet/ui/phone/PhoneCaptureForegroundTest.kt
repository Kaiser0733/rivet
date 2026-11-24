package com.kaiser.rivet.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCaptureForegroundTest {
    @Test fun aHiddenRivetNeedsTheForegroundInsteadOfBeingRaised() {
        assertTrue(phoneShotNeedsForeground(rivetVisible = false))
        assertFalse(phoneShotNeedsForeground(rivetVisible = true))
    }

    @Test fun anAcceptedLaunchProceedsToConsent() {
        assertNull(phoneShotFailureCode(launchAccepted = true, refusedWhileResumed = false, timedOut = false))
    }

    @Test fun aRefusalWhileResumedIsGenuinelyUnavailable() {
        assertEquals("phone_shot_unavailable",
            phoneShotFailureCode(launchAccepted = false, refusedWhileResumed = true, timedOut = false))
    }

    @Test fun anExpiredWaitNeedsTheForeground() {
        assertEquals("phone_shot_needs_foreground",
            phoneShotFailureCode(launchAccepted = false, refusedWhileResumed = false, timedOut = true))
    }

    @Test fun anUnexpiredWaitKeepsWaiting() {
        assertNull(phoneShotFailureCode(launchAccepted = false, refusedWhileResumed = false, timedOut = false))
    }

    @Test fun theForegroundWaitIsBoundedToSixtySeconds() {
        assertEquals(60_000L, PHONE_SHOT_FOREGROUND_WAIT_MS)
    }
}
