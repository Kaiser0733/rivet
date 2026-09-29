package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectBindingTest {
    @Test fun exactAccessibleIdentityMatches() {
        assertEquals(ProjectBindingState.Matched,
            projectBindingState("session", "content://provider/tree/one", "content://provider/tree/one", false))
    }

    @Test fun unavailableBoundProjectIsAccessLostNotMismatch() {
        assertEquals(ProjectBindingState.AccessLost,
            projectBindingState("session", "content://provider/tree/one", null, false))
    }

    @Test fun differentExactIdentityIsMismatchEvenIfDisplayNamesMightMatch() {
        assertEquals(ProjectBindingState.Mismatch,
            projectBindingState("session", "content://provider/tree/one", "content://other/tree/one", false))
    }

    @Test fun loadingDoesNotPresentAnAccessFailure() {
        assertEquals(ProjectBindingState.Loading,
            projectBindingState("session", "content://provider/tree/one", null, true))
    }

    @Test fun unboundSessionWithNoProjectIsNeutral() {
        assertEquals(ProjectBindingState.None,
            projectBindingState("session", null, null, false))
    }

    @Test fun unboundSessionDoesNotSilentlyAttachToSelectedProject() {
        assertEquals(ProjectBindingState.Mismatch,
            projectBindingState("session", null, "content://provider/tree/one", false))
    }
}
