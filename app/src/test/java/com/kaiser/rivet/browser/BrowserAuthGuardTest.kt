package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAuthGuardTest {
    @Test fun googleAndMicrosoftLoginsNeedHandoff() {
        assertTrue(BrowserAuthGuard.isAuthSurface("https://accounts.google.com/signin/v2/identifier"))
        assertTrue(BrowserAuthGuard.isAuthSurface("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=x&redirect_uri=y"))
    }

    @Test fun githubOwnLoginAndOauthAuthorizeNeedHandoff() {
        assertTrue(BrowserAuthGuard.isAuthSurface("https://github.com/login"))
        assertTrue(BrowserAuthGuard.isAuthSurface("https://github.com/login/oauth/authorize?client_id=x&redirect_uri=y"))
    }

    @Test fun oauthShapedPathsNeedHandoffEvenOnAnotherHost() {
        assertTrue(BrowserAuthGuard.isAuthSurface("https://example.com/oauth/authorize?client_id=abc&redirect_uri=https%3A%2F%2Fexample.com"))
        assertFalse(BrowserAuthGuard.isAuthSurface("https://example.com/oauth/docs"))
    }

    @Test fun checkoutHostsNeedHandoff() {
        assertTrue(BrowserAuthGuard.isAuthSurface("https://checkout.stripe.com/c/pay/cs_test"))
        assertTrue(BrowserAuthGuard.isAuthSurface("https://www.paypal.com/checkoutnow"))
    }

    @Test fun normalDocumentationIsNotAnAuthSurface() {
        assertFalse(BrowserAuthGuard.isAuthSurface("https://developer.android.com/topic/performance/memory/guide/webview-memory"))
        assertFalse(BrowserAuthGuard.isAuthSurface("https://github.com/Kaiser0733/rivet"))
        assertFalse(BrowserAuthGuard.isAuthSurface("http://127.0.0.1:8123/index.html"))
    }

    @Test fun aPasswordFieldIsAPageSignalEvenWithoutAnAuthHost() {
        val html = """<form><input type="password" name="pw"></form>"""
        assertTrue(BrowserAuthGuard.hasPasswordField(html))
        assertTrue(BrowserAuthGuard.needsHandoff("https://example.com/account", html))
    }

    @Test fun loopbackRecognizesBracketedIpv6AndMixedCaseHosts() {
        assertTrue(BrowserAuthGuard.isLoopback("http://[::1]:8123/index.html"))
        assertTrue(BrowserAuthGuard.isLoopback("http://LOCALHOST:3000/"))
        assertFalse(BrowserAuthGuard.isLoopback("http://[::1]/oauth/authorize?client_id=x&redirect_uri=y") &&
            BrowserAuthGuard.isAuthSurface("http://[::1]/oauth/authorize?client_id=x&redirect_uri=y"))
    }
}
