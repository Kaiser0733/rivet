package com.kaiser.rivet.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRedactorTest {
    @Test
    fun preservesUserAndPasswordFlagsWhileRedactingValues() {
        val redacted = CommandRedactor.redact("git clone https://example.com --user alice --password secret")
        assertTrue(redacted, redacted.contains("--user [redacted]"))
        assertTrue(redacted, redacted.contains("--password [redacted]"))
        assertFalse(redacted, redacted.contains("alice"))
        assertFalse(redacted, redacted.contains("secret"))
    }

    @Test
    fun redactsQuotedAndUnquotedBearerAuthorizationTokensWithoutLeakingSecret() {
        val quotedHeader = CommandRedactor.redact("curl -H \"Authorization: Bearer topsecret123\" https://api.example.com")
        assertFalse(quotedHeader, quotedHeader.contains("topsecret123"))
        assertTrue(quotedHeader, quotedHeader.contains("\"Authorization:"))

        val quotedBearer = CommandRedactor.redact("curl -H 'Authorization: \"Bearer tok_xyz\"' https://api.example.com")
        assertFalse(quotedBearer, quotedBearer.contains("tok_xyz"))
    }
}
