package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderCacheTest {
    private fun doc(url: String, text: String) = ReaderDocument(url, "title", text,
        listOf(ReaderChunk(0, 1, "a", text)), needsRenderer = false)

    @Test fun aNavigationInvalidatesThePreNavigationDocument() {
        val cache = ReaderCache()
        cache.record(cache.navigationToken, doc("https://a", "old"))
        assertEquals("old", cache.cached()?.text)
        cache.navigate()
        assertNull(cache.cached())
        cache.record(cache.navigationToken, doc("https://a", "new"))
        assertEquals("new", cache.cached()?.text)
    }

    @Test fun anExtractionThatFinishedAgainstAnOldTokenIsRejected() {
        val cache = ReaderCache()
        val old = cache.navigationToken
        cache.navigate()
        assertEquals(false, cache.record(old, doc("https://a", "old")))
        assertNull(cache.cached())
    }
}
