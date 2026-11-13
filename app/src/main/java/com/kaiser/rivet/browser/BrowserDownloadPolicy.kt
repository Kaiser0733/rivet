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

    fun extractFileName(disposition: String?, url: String): String {
        val raw = disposition?.let(::parseDispositionFilename)?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/')
        return sanitizeFileName(raw)
    }

    private fun parseDispositionFilename(disposition: String): String? {
        var plain: String? = null
        for (part in disposition.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim().lowercase()
            val value = part.substring(idx + 1).trim()
            if (key == "filename*") {
                val decoded = value.substringAfter("''", value).trim('"', '\'', ' ')
                if (decoded.isNotBlank()) return decoded
            } else if (key == "filename" && plain == null) {
                val unquoted = value.trim('"', '\'', ' ')
                if (unquoted.isNotBlank()) plain = unquoted
            }
        }
        return plain
    }

    fun sanitizeFileName(raw: String?): String {
        val stripped = raw.orEmpty()
            .substringBefore('?')
            .substringBefore('#')
        val decoded = runCatching {
            java.net.URLDecoder.decode(stripped.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(stripped)
        val cleaned = decoded
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .map { ch ->
                if (ch.isISOControl() || ch in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')) '_'
                else ch
            }
            .joinToString("")
            .trim('.', ' ')
            .take(120)
            .trim('.', ' ')
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "download" else cleaned
    }
}
