package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAttachPolicyTest {
    @Test fun aFreshAttachOnlyAttaches() {
        assertEquals(BrowserAttachAction.Attach, BrowserAttachPolicy.onAttach(oldAttached = false))
    }

    @Test fun aReplacementLogsAndReleasesTheFieldWithoutDestroying() {
        assertEquals(BrowserAttachAction.ReleaseFieldOnly,
            BrowserAttachPolicy.onAttach(oldAttached = true))
    }
}
