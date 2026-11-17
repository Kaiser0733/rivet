package com.kaiser.rivet.browser

/**
 * Reader documents are bound to the navigation that produced them. A navigation
 * mints a new token; an old document is never served through it. The visible-only
 * variant is part of the cache key, not a post-hoc filter, because re-reading a DOM
 * with hidden text cannot produce the visible-only document again.
 */
class ReaderCache {
    var navigationToken: Long = 0L
        private set
    private var documentToken: Long = -1L
    private var documentVisibleOnly: Boolean? = null
    private var document: ReaderDocument? = null
    private var rawToken: Long = -1L
    private var raw: String? = null

    fun navigate(): Long {
        navigationToken++
        clear()
        return navigationToken
    }

    fun cached(visibleOnly: Boolean = true): ReaderDocument? =
        document?.takeIf {
            documentToken == navigationToken && documentVisibleOnly == visibleOnly
        }

    fun record(token: Long, value: ReaderDocument, visibleOnly: Boolean = true): Boolean {
        if (token != navigationToken) return false
        documentToken = token
        documentVisibleOnly = visibleOnly
        document = value
        return true
    }

    /**
     * Stores the source HTML so a text-mode page can be re-extracted for the other
     * visible_only variant without a second network request.
     */
    fun recordRaw(token: Long, html: String): Boolean {
        if (token != navigationToken) return false
        rawToken = token
        raw = html.take(MAX_RAW_HTML_CHARS)
        return true
    }

    fun rawHtml(): String? = raw?.takeIf { rawToken == navigationToken }

    fun invalidate() {
        clear()
    }

    private fun clear() {
        document = null
        documentToken = -1L
        documentVisibleOnly = null
        raw = null
        rawToken = -1L
    }

    companion object {
        const val MAX_RAW_HTML_CHARS = 2_000_000
    }
}
