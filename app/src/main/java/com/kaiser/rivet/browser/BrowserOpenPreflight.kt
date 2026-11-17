package com.kaiser.rivet.browser

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

data class LoopbackProbe(
    val httpStatus: Int,
    val warning: String? = null,
)

/**
 * URL normalization and loopback reachability checks that run before a WebView is
 * created. Android's cleartext policy rejects plain http for public hosts; naming that
 * fix here is much cheaper than a dead ERR_CLEARTEXT page the agent cannot inspect.
 */
object BrowserOpenPreflight {
    private val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val typedLoopback = Regex(
        "^(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::\\d+)?(?:/|$)",
        RegexOption.IGNORE_CASE,
    )
    private val wildcard = Regex("^0\\.0\\.0\\.0(?::\\d+)?(?:/|$)", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) {
            throw BrowserFailure("browser_invalid_url", "Enter a page address.")
        }
        if (wildcard.containsMatchIn(input) || input.startsWith("http://0.0.0.0", true) ||
            input.startsWith("https://0.0.0.0", true)) {
            throw BrowserFailure(
                "browser_invalid_url",
                "0.0.0.0 is not reachable from the browser. Use " +
                    "http://127.0.0.1:PORT/ or preview_root/preview_entry.",
            )
        }
        val httpCandidate = when {
            hasScheme.containsMatchIn(input) -> input
            typedLoopback.containsMatchIn(input) -> "http://$input"
            else -> input
        }
        val parsed = runCatching { URI(httpCandidate) }.getOrNull()
        val host = parsed?.host?.trim('[', ']')?.lowercase(Locale.ROOT).orEmpty()
        if (host == "0.0.0.0") {
            throw BrowserFailure(
                "browser_invalid_url",
                "0.0.0.0 is not reachable from the browser. Use " +
                    "http://127.0.0.1:PORT/ or preview_root/preview_entry.",
            )
        }
        val validated = BrowserUrlPolicy.validate(httpCandidate)
        val uri = URI(validated)
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme == "http" && !BrowserAuthGuard.isLoopback(validated)) {
            throw BrowserFailure(
                "browser_cleartext_blocked",
                "Plain http:// is blocked by Android network security for non-loopback " +
                    "hosts. Use https:// for public pages, or use " +
                    "preview_root/preview_entry (or http://127.0.0.1:PORT/) for local " +
                    "project files.",
            )
        }
        return validated
    }

    /**
     * Probes a loopback URL with a HEAD request (GET only when HEAD is not allowed).
     * A 4xx/5xx is returned rather than thrown: a dev server's own error page can still
     * be useful, and the caller must be told the real status instead of pretending 200.
     */
    fun probeLoopback(
        raw: String,
        connectTimeoutMs: Int = 1_000,
        readTimeoutMs: Int = 2_000,
    ): LoopbackProbe {
        val url = normalize(raw)
        if (!BrowserAuthGuard.isLoopback(url)) {
            throw BrowserFailure(
                "browser_invalid_url",
                "Loopback probing only applies to 127.0.0.1, localhost, or [::1].",
            )
        }
        val status = try {
            request(url, "HEAD", connectTimeoutMs, readTimeoutMs).let { code ->
                if (code == HttpURLConnection.HTTP_BAD_METHOD) {
                    request(url, "GET", connectTimeoutMs, readTimeoutMs)
                } else code
            }
        } catch (_: IOException) {
            throw BrowserFailure(
                "browser_loopback_unreachable",
                "Nothing is listening at $url. Start a static preview with " +
                    "preview_root/preview_entry (or start_preview), or start a server with " +
                    "spawn_process.",
            )
        }
        val warning = if (status >= 400) {
            "Server returned HTTP $status. Check preview_entry or server routes."
        } else null
        return LoopbackProbe(status, warning)
    }

    private fun request(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs.coerceAtLeast(1)
            connection.readTimeout = readTimeoutMs.coerceAtLeast(1)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            runCatching { connection.inputStream?.close() }
            runCatching { connection.errorStream?.close() }
            if (code !in 100..599) throw IOException("No HTTP status from $url")
            code
        } finally {
            connection.disconnect()
        }
    }
}
