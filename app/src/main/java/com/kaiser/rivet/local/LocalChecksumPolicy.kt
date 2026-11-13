package com.kaiser.rivet.local

import java.util.Locale

internal object LocalChecksumPolicy {
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun isPublishedChecksumVerified(
        expectedSha256: String?,
        catalogSha256: String?,
        etagSha256: String?,
        byo: Boolean,
        actualSha256Matched: Boolean,
    ): Boolean {
        if (!actualSha256Matched) return false
        val trustedPin = expectedSha256?.takeIf { SHA256_REGEX.matches(it) }
            ?: if (!byo) catalogSha256?.takeIf { SHA256_REGEX.matches(it) } else null
        return trustedPin != null
    }

    fun sanitizeRelativeGgufPath(rawPath: String): String? {
        if (rawPath.isBlank() || rawPath.startsWith('/') || rawPath.endsWith('/') ||
            '\\' in rawPath || '\u0000' in rawPath
        ) {
            return null
        }
        val segments = rawPath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        val normalized = segments.joinToString("/")
        return normalized.takeIf { it.lowercase(Locale.ROOT).endsWith(".gguf") }
    }
}
