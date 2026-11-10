package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadPolicyTest {
    @Test fun largeFileOnMeteredNetworkAsksFirst() {
        assertTrue(BrowserDownloadPolicy.asksFirst(10L * 1024 * 1024 + 1, metered = true))
        assertFalse(BrowserDownloadPolicy.asksFirst(10L * 1024 * 1024, metered = true))
        assertFalse(BrowserDownloadPolicy.asksFirst(10L * 1024 * 1024 + 1, metered = false))
    }
}
