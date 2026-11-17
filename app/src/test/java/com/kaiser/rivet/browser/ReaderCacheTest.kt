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

    @Test fun aHiddenTextCacheIsNeverServedAsAVisibleDocument() {
        val cache = ReaderCache()
        cache.record(cache.navigationToken, doc("https://a", "with hidden"))
        cache.record(cache.navigationToken, doc("https://a", "without hidden"),
            visibleOnly = true)
        assertEquals("without hidden", cache.cached(visibleOnly = true)?.text)
        cache.record(cache.navigationToken, doc("https://a", "with hidden again"),
            visibleOnly = false)
        assertEquals("with hidden again", cache.cached(visibleOnly = false)?.text)
        assertNull(cache.cached(visibleOnly = true))
    }

    @Test fun rawHtmlIsBoundToTheNavigationAndSupportsReextraction() {
        val cache = ReaderCache()
        val token = cache.navigationToken
        assertEquals(true, cache.recordRaw(token, "<html><body>raw</body></html>"))
        assertEquals("<html><body>raw</body></html>", cache.rawHtml())
        cache.navigate()
        assertNull(cache.rawHtml())
        assertEquals(false, cache.recordRaw(token, "<html>old</html>"))
        assertNull(cache.rawHtml())
        cache.recordRaw(cache.navigationToken, "<html><body>new</body></html>")
        assertEquals("<html><body>new</body></html>", cache.rawHtml())
        cache.invalidate()
        assertNull(cache.rawHtml())
    }

    @Test fun navigationClearsBothVisibilityVariants() {
        val cache = ReaderCache()
        cache.record(cache.navigationToken, doc("https://a", "hidden"), visibleOnly = false)
        assertNull(cache.cached(visibleOnly = true))
        cache.navigate()
        assertNull(cache.cached(visibleOnly = false))
        assertNull(cache.rawHtml())
    }
}
