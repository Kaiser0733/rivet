package com.kaiser.rivet.browser

/**
 * Reader documents are bound to the navigation that produced them. A navigation
 * mints a new token; an old document is never served through it.
 */
class ReaderCache {
    var navigationToken: Long = 0L
        private set
    private var documentToken: Long = -1L
    private var document: ReaderDocument? = null

    fun navigate(): Long {
        navigationToken++
        document = null
        documentToken = -1L
        return navigationToken
    }

    fun cached(): ReaderDocument? =
        document?.takeIf { documentToken == navigationToken }

    fun record(token: Long, value: ReaderDocument): Boolean {
        if (token != navigationToken) return false
        documentToken = token
        document = value
        return true
    }

    fun invalidate() {
        document = null
        documentToken = -1L
    }
}
