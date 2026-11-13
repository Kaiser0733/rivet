package com.kaiser.rivet.storage

import java.net.URI
import java.util.Locale

internal object LocalEndpointPolicy {
    fun isLocal(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.lowercase(Locale.ROOT).startsWith("local://")) return true
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return false
        val host = uri.host?.trim('[', ']')?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        val ipv4 = parseIpv4(host) ?: return false
        return when (ipv4[0]) {
            10, 127 -> true
            192 -> ipv4[1] == 168
            172 -> ipv4[1] in 16..31
            else -> false
        }
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || (part.length > 1 && part[0] == '0')) return null
            val num = part.toIntOrNull() ?: return null
            if (num !in 0..255) return null
            octets[i] = num
        }
        return octets
    }
}
