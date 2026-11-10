package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.storage.SessionUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class FooterPresentationTest {
    private fun usage(reported: Int, unknown: Int, title: Int = 0,
                      compaction: Int = 0, compactionUnknown: Int = 0) = SessionUsage(
        reportedInputTokens = null, reportedOutputTokens = null,
        reportedRequests = reported, unknownRequests = unknown,
        latestInputTokens = null, latestSource = null,
        compactionRequests = compaction, compactionUnknownRequests = compactionUnknown,
        titleRequests = title,
    )

    @Test fun localUnknownUsageStillCountsAsACompletedTurn() {
        assertEquals(1, footerTurnCount(usage(reported = 0, unknown = 1)))
        assertEquals(3, footerTurnCount(usage(reported = 2, unknown = 1)))
    }

    @Test fun titleAndCompactionRequestsAreNotTurns() {
        // 5 requests minus 1 title minus 1 compaction. compactionUnknown is already
        // part of unknownRequests and compactionRequests, so it must not be counted twice.
        assertEquals(3, footerTurnCount(usage(reported = 3, unknown = 2, title = 1,
            compaction = 1, compactionUnknown = 1)))
        assertEquals(2, footerTurnCount(usage(reported = 3, unknown = 2, title = 1,
            compaction = 2, compactionUnknown = 0)))
    }

    @Test fun noSessionIsZero() {
        assertEquals(0, footerTurnCount(null))
    }
}
