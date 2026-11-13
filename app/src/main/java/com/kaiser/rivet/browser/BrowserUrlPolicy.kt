package com.kaiser.rivet.browser

import java.util.Locale

class BrowserFailure(val code: String, override val message: String = code) : Exception(message)

object BrowserUrlPolicy {
    fun validate(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) throw BrowserFailure("browser_invalid_url", "Enter a page address.")
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: throw BrowserFailure("browser_invalid_url", "That page address is not valid.")
        if (uri.userInfo != null) throw BrowserFailure("browser_invalid_url", "Page addresses with credentials are not allowed.")
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
            throw BrowserFailure("browser_scheme", "Rivet's browser opens http and https pages. Project previews use the local preview server.")
        }
        if (uri.host.isNullOrBlank()) throw BrowserFailure("browser_invalid_url", "That page has no host.")
        return uri.toString()
    }

    fun requireReadable(raw: String): String = validate(raw)
}
