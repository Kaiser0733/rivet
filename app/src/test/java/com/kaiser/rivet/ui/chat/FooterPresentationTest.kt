package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.storage.SessionUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class FooterPresentationTest {
    private fun usage(reported: Int, unknown: Int) = SessionUsage(
        reportedInputTokens = null, reportedOutputTokens = null,
        reportedRequests = reported, unknownRequests = unknown,
        latestInputTokens = null, latestSource = null,
    )

    @Test fun localUnknownUsageStillCountsAsACompletedTurn() {
        assertEquals(1, footerTurnCount(usage(reported = 0, unknown = 1)))
        assertEquals(3, footerTurnCount(usage(reported = 2, unknown = 1)))
    }

    @Test fun noSessionIsZero() {
        assertEquals(0, footerTurnCount(null))
    }
}
