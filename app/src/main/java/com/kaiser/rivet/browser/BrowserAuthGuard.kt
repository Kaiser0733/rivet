package com.kaiser.rivet.browser

import java.net.URI
import java.util.Locale

/**
 * Pure detection of surfaces where Rivet must hand identity work to the phone's
 * browser. It never spoofs a user agent and never suggests a cookie flows back.
 */
object BrowserAuthGuard {
    private val AUTH_HOSTS = setOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "login.microsoftonline.com",
        "login.live.com",
        "appleid.apple.com",
        "idmsa.apple.com",
        "github.com",
        "paypal.com",
        "www.paypal.com",
        "checkout.stripe.com",
        "buy.stripe.com",
        "commerce.coinbase.com",
    )
    private val AUTH_PATH_HINTS = listOf(
        "/login", "/signin", "/sign-in", "/sessions", "/oauth", "/authorize",
        "/checkout", "/payment", "/billing/pay", "/account/security",
    )
    private val CHECKOUT_HOSTS = setOf(
        "checkout.stripe.com", "buy.stripe.com", "paypal.com", "www.paypal.com",
        "commerce.coinbase.com", "checkout.shopify.com",
    )

    fun isLoopback(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT) ?: return@runCatching false
        host in setOf("127.0.0.1", "localhost", "::1")
    }.getOrDefault(false)

    fun isAuthSurface(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (isLoopback(url)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (host in CHECKOUT_HOSTS) return true
        if (host in setOf("accounts.google.com", "accounts.youtube.com",
                "login.microsoftonline.com", "login.live.com", "appleid.apple.com")) return true
        if (host == "github.com" && (path.startsWith("/login") || path.startsWith("/sessions"))) return true
        if (pathContainsOauthShape(uri)) return true
        if (host in AUTH_HOSTS && AUTH_PATH_HINTS.any { path.startsWith(it) }) return true
        return false
    }

    fun hasPasswordField(html: String): Boolean =
        Regex("(?is)<input[^>]+type\\s*=\\s*[\"']?password").containsMatchIn(html)

    fun needsHandoff(url: String, html: String? = null): Boolean =
        isAuthSurface(url) || (html != null && hasPasswordField(html))

    fun isOAuthShape(url: String): Boolean = parse(url)?.let(::pathContainsOauthShape) == true

    private fun pathContainsOauthShape(uri: URI): Boolean {
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if ("/oauth" !in path && !path.contains("authorize")) return false
        val query = uri.rawQuery?.lowercase(Locale.ROOT).orEmpty()
        return query.contains("client_id=") && query.contains("redirect_uri=")
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()
}
