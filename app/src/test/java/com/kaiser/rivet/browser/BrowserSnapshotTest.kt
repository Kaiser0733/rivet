package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSnapshotTest {
    @Test fun snapshotRoundTripsHistoryFormAndFidelity() {
        val snapshot = BrowserSnapshot("a", "Docs", listOf("https://a", "https://b"), 1,
            420, mapOf("q" to "kotlin"), fidelity = BrowserFidelity.Full)
        val decoded = BrowserSnapshotCodec.decode(BrowserSnapshotCodec.encode(snapshot))!!
        assertEquals(snapshot, decoded.copy(createdAt = snapshot.createdAt, lastUsedAt = snapshot.lastUsedAt))
        assertEquals("https://b", decoded.currentUrl)
    }

    @Test fun restoredWindowWithNoNativeStackBacksToThePreviousModelUrl() {
        val restored = BrowserSnapshot("restored", historyUrls =
            listOf("https://one.example", "https://two.example"), historyIndex = 1)
        assertEquals(BrowserBackTarget("https://one.example", 0), restored.backTarget())
    }

    @Test fun restoredWindowAtTheStartOfItsModelFailsBackCleanly() {
        val restored = BrowserSnapshot("restored", historyUrls = listOf("https://one.example"), historyIndex = 0)
        assertNull(restored.backTarget())
    }

    @Test fun evictionKeepsTheActiveWindowAndOldestInactiveGoesFirst() {
        val list = (1..9).map { BrowserSnapshot("$it", lastUsedAt = it.toLong()) }
        val capped = BrowserSnapshotCodec.capWindows(list, activeId = "1")
        assertEquals(8, capped.size)
        assertTrue(capped.any { it.id == "1" })
        val bytes = BrowserSnapshotCodec.evictForBytes(list, { 4L * 1024 * 1024 }, activeId = "9", maxBytes = 12L * 1024 * 1024)
        assertEquals(3, bytes.size)
        assertTrue(bytes.any { it.id == "9" })
    }
}
