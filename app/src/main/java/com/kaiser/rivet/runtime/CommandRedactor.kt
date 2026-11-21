package com.kaiser.rivet.runtime

internal object CommandRedactor {
    fun redact(command: String, oneLine: Boolean = true): String {
        val redacted = command
            .replace(Regex("(?i)(api[_-]?key|token|password|secret)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s]+)"), "$1$2[redacted]")
            .replace(Regex("(?i)(https?://)[^/@\\s]+@"), "$1[redacted]@")
            .replace(Regex("(?i)(--user(?:name)?|--password)(\\s+|=)(\"[^\"]*\"|'[^']*'|[^\\s]+)"), "$1$2[redacted]")
            .replace(Regex("(?i)(^|\\s)(-u)(\\s+)(\"[^\"]*\"|'[^']*'|[^\\s]+)"), "$1$2$3[redacted]")
            .replace(
                Regex("(?i)(authorization\\s*[:=]\\s*(?:[\"']|\\\\\")?(?:(?:bearer|basic|token)\\s+)?)([^\\s\"']+)"),
                "$1[redacted]",
            )
        return if (oneLine) {
            redacted.replace(Regex("[\\r\\n\\u0000-\\u001f]"), " ")
        } else {
            redacted.replace("\r\n", "\n").replace('\r', '\n')
                .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        }
    }
}
