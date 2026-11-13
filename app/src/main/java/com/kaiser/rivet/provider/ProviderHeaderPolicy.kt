package com.kaiser.rivet.provider

/**
 * HTTP header validation and sanitization rules for custom provider headers.
 * Keeps `Authorization`, `x-api-key`, and `x-goog-api-key` reserved for the transport
 * and rejects malformed RFC 7230 names or OkHttp-disallowed control/non-ASCII values.
 */
internal object ProviderHeaderPolicy {

    private val HEADER_NAME_REGEX = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

    fun isReservedAuthHeader(name: String): Boolean {
        val n = name.trim().lowercase()
        return n == "authorization" || n == "x-api-key" || n == "x-goog-api-key"
    }

    fun isValidHeaderName(name: String): Boolean = HEADER_NAME_REGEX.matches(name)

    fun isValidHeaderValue(value: String): Boolean =
        value.all { ch -> ch == '\t' || ch in '\u0020'..'\u007e' }

    fun isPermittedCustomHeader(name: String, value: String): Boolean =
        !isReservedAuthHeader(name) && isValidHeaderName(name) && isValidHeaderValue(value)
}
