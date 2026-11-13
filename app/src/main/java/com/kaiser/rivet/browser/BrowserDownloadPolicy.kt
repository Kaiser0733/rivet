package com.kaiser.rivet.browser

import java.net.URI

/** Pages never ask; a large file on a metered network asks once. */
object BrowserDownloadPolicy {
    const val ASK_ABOVE_BYTES = 10L * 1024 * 1024

    fun asksFirst(sizeBytes: Long, metered: Boolean): Boolean =
        metered && sizeBytes > ASK_ABOVE_BYTES

    fun isSupportedDownloadUrl(url: String?): Boolean {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val scheme = runCatching { URI(trimmed).scheme }.getOrNull()
            ?: trimmed.substringBefore(':', "").takeIf { it.isNotEmpty() && !it.contains('/') }
            ?: return false
        return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
    }

    fun sanitizeFileName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .map { ch ->
                if (ch.isISOControl() || ch in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')) '_'
                else ch
            }
            .joinToString("")
            .trim()
            .trimStart('.')
            .take(120)
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "download" else cleaned
    }
}
