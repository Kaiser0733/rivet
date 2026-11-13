package com.kaiser.rivet.runtime

internal object LocalPreviewPolicy {
    fun isDisallowedGitSegment(segment: String): Boolean =
        segment.equals(".git", ignoreCase = true)

    fun isLoopbackHostHeader(rawHost: String?, boundPort: Int): Boolean {
        val value = rawHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val host: String
        val portPart: String?
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) return false
            host = value.substring(0, close + 1)
            val rest = value.substring(close + 1)
            portPart = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return false
            }
        } else {
            val colon = value.indexOf(':')
            if (colon < 0) {
                host = value
                portPart = null
            } else {
                if (value.indexOf(':', colon + 1) >= 0) return false
                host = value.substring(0, colon)
                portPart = value.substring(colon + 1)
            }
        }
        if (host != "127.0.0.1" && host != "localhost" && host != "[::1]") return false
        if (portPart == null) return true
        val parsedPort = portPart.toIntOrNull() ?: return false
        return boundPort <= 0 || parsedPort == boundPort
    }
}
