package com.kaiser.rivet.browser

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class BrowserFailure(val code: String, override val message: String = code) : Exception(message)

object BrowserUrlPolicy {
    const val START_PAGE = "https://www.google.com/"

    fun addressBarTarget(raw: String): String {
        val input = raw.trim()
        val explicit = runCatching { URI(input) }.getOrNull()
        if (explicit?.scheme != null && explicit.rawAuthority != null) return input

        val candidate = runCatching { URI("https://$input") }.getOrNull()
        val host = candidate?.host.orEmpty()
        if (candidate != null && candidate.rawUserInfo == null && (host.equals("localhost", ignoreCase = true) ||
                '.' in host || host.startsWith('['))) {
            return if (BrowserAuthGuard.isLoopback(candidate.toString())) "http://$input"
            else candidate.toString()
        }

        return "${START_PAGE}search?q=${URLEncoder.encode(input, Charsets.UTF_8.name())}"
    }

    /** Typed loopback URLs are normalized, wildcard hosts and cleartext are named. */
    fun normalize(raw: String): String = BrowserOpenPreflight.normalize(raw)

    fun validate(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) throw BrowserFailure("browser_invalid_url", "Enter a page address.")
        val uri = runCatching { URI(url) }.getOrNull()
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
