package com.kaiser.rivet.runtime

import com.kaiser.rivet.runtime.PhoneCaptureEvent.*
import com.kaiser.rivet.runtime.PhoneCaptureState.*
import org.junit.Assert.*
import org.junit.Test

class PhoneCaptureStateTest {
    @Test fun captureRequiresConsentAndExactlyOneFrame() {
        assertNull(Idle.transition(Capture))
        assertEquals(Armed, Idle.transition(Request))
        assertNull(Armed.transition(Capture))
        assertEquals(Ready, Armed.transition(Consent))
        assertTrue(Ready.canStartService)
        assertNull(Ready.transition(Consent))
        assertEquals(Capturing, Ready.transition(Capture))
        assertNull(Capturing.transition(Capture))
        assertEquals(Done, Capturing.transition(Finish))
        assertNull(Done.transition(Capture))
    }

    @Test fun denialNeverStartsServiceAndCancellationEndsAnyPendingCapture() {
        val denied = Armed.transition(Deny)
        assertEquals(Denied, denied)
        assertFalse(requireNotNull(denied).canStartService)
        assertNull(denied.transition(Consent))
        listOf(Armed, Ready, Capturing).forEach { assertEquals(Cancelled, it.transition(Cancel)) }
        assertNull(Cancelled.transition(Capture))
    }
}
