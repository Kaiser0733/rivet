package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ChatGptPkceTest {

    @Test fun rfc7636AppendixBVector() {
        // https://www.rfc-editor.org/rfc/rfc7636#appendix-B
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", codeChallenge(verifier))
    }

    @Test fun verifierShape() {
        val verifier = generateCodeVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse('=' in verifier)
    }

    @Test fun verifierIsRandom() {
        assertTrue(generateCodeVerifier() != generateCodeVerifier())
    }

    @Test fun randomTokenShape() {
        val token = randomToken()
        assertFalse('=' in token)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
        // 32 bytes -> ceil(32/3)*4 = 44 chars, no padding.
        assertEquals(43, token.length)
        val decoded = Base64.getUrlDecoder().decode(token)
        assertEquals(32, decoded.size)
    }

    @Test fun parseTokenResponseOk() {
        val body = """{"access_token":"a","refresh_token":"r","id_token":"i",
            "expires_in":3600,"scope":"openid chatgpt.tokens.use.direct"}"""
        val result = parseTokenResponse(body)
        assertTrue(result is TokenResult.Ok)
        val ok = result as TokenResult.Ok
        assertEquals("a", ok.accessToken)
        assertEquals("r", ok.refreshToken)
        assertEquals(3600L, ok.expiresInSec)
    }

    @Test fun parseTokenResponseError() {
        val result = parseTokenResponse("""{"error":"invalid_grant"}""")
        assertTrue(result is TokenResult.Failed)
        assertEquals("invalid_grant", (result as TokenResult.Failed).error)
    }

    @Test fun parseTokenResponseMalformed() {
        assertTrue(parseTokenResponse("not json") is TokenResult.Failed)
        assertTrue(parseTokenResponse("""{"access_token":"a"}""") is TokenResult.Failed)
    }

    @Test fun hasPlanScope() {
        assertTrue(hasPlanScope("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"))
        assertFalse(hasPlanScope("openid profile email offline_access"))
        assertFalse(hasPlanScope(""))
        // Substring must not match: exact token only.
        assertFalse(hasPlanScope("chatgpt.tokens.use.directx"))
    }
}
