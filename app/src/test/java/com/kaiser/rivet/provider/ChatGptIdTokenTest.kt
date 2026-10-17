package com.kaiser.rivet.provider

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ChatGptBundleTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun serializationRoundTrip() {
        val bundle = ChatGptBundle(
            clientId = "oaiapp_test",
            accessToken = "at",
            refreshToken = "rt",
            idToken = "idt",
            expiresInSec = 3600,
            savedAtMs = 1_700_000_000_000L,
            scopes = listOf("openid", "chatgpt.tokens.use.direct"),
            sub = "user_123",
            email = "a@b.c",
        )
        val encoded = json.encodeToString(bundle)
        assertEquals(bundle, json.decodeFromString<ChatGptBundle>(encoded))
    }

    @Test fun accessTokenExpiryMargin() {
        val now = 1_700_000_000_000L
        val fresh = ChatGptBundle("c", "a", "r", "i", 3600, now, emptyList(), "s", null)
        assertFalse(fresh.accessTokenExpired(now))
        // Inside the 60s margin counts as expired.
        assertTrue(fresh.accessTokenExpired(now + 3_540_000L))
        assertTrue(fresh.accessTokenExpired(now + 3_700_000L))
    }
}

class ChatGptIdTokenTest {

    private val enc = Base64.getUrlEncoder().withoutPadding()

    private fun b64(obj: String) = enc.encodeToString(obj.toByteArray(Charsets.UTF_8))

    /** Builds a signed JWT + matching JWKS JSON for the given claims. */
    private fun mint(
        claims: String,
        kid: String = "k1",
        alg: String = "RS256",
        tamperSig: Boolean = false,
    ): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val rsaPub = pair.public as java.security.interfaces.RSAPublicKey
        val header = b64("""{"alg":"$alg","kid":"$kid","typ":"JWT"}""")
        val payload = b64(claims)
        val signingInput = "$header.$payload".toByteArray(Charsets.US_ASCII)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(pair.private)
        sig.update(signingInput)
        val sigBytes = sig.sign()
        if (tamperSig) sigBytes[0] = (sigBytes[0] + 1).toByte()
        val token = "$header.$payload.${enc.encodeToString(sigBytes)}"

        // JWK needs unsigned big-endian n/e; strip BigInteger's sign byte.
        fun strip(v: BigInteger) = enc.encodeToString(v.toByteArray().let {
            if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        })
        val jwks = """{"keys":[{"kty":"RSA","kid":"$kid","n":"${strip(rsaPub.modulus)}","e":"${strip(rsaPub.publicExponent)}"}]}"
        return token to jwks
    }

    private fun claims(
        iss: String = "https://auth.openai.com",
        aud: String = "\"oaiapp_x\"",
        exp: Long = 2_000_000_000L,
        nonce: String = "n123",
        sub: String = "user_1",
    ) = """{"iss":"$iss","aud":$aud,"exp":$exp,"nonce":"$nonce","sub":"$sub","email":"a@b.c"}"""

    private val nowMs = 1_700_000_000_000L // 2023-11-14, well before exp

    @Test fun validTokenReturnsSub() {
        val (token, jwks) = mint(claims())
        assertEquals("user_1", validateIdToken(token, "oaiapp_x", "n123", jwks, nowMs))
    }

    @Test fun wrongAudRejected() {
        val (token, jwks) = mint(claims())
        assertNull(validateIdToken(token, "oaiapp_other", "n123", jwks, nowMs))
    }

    @Test fun expiredRejected() {
        val (token, jwks) = mint(claims(exp = 1_000L))
        assertNull(validateIdToken(token, "oaiapp_x", "n123", jwks, nowMs))
    }

    @Test fun wrongNonceRejected() {
        val (token, jwks) = mint(claims())
        assertNull(validateIdToken(token, "oaiapp_x", "nope", jwks, nowMs))
    }

    @Test fun wrongIssRejected() {
        val (token, jwks) = mint(claims(iss = "https://evil.example"))
        assertNull(validateIdToken(token, "oaiapp_x", "n123", jwks, nowMs))
    }

    @Test fun tamperedSignatureRejected() {
        val (token, jwks) = mint(claims(), tamperSig = true)
        assertNull(validateIdToken(token, "oaiapp_x", "n123", jwks, nowMs))
    }

    @Test fun unknownKidRejected() {
        val (token, _) = mint(claims(), kid = "k1")
        val (_, jwks2) = mint(claims(), kid = "k2")
        assertNull(validateIdToken(token, "oaiapp_x", "n123", jwks2, nowMs))
    }

    @Test fun nonRs256Rejected() {
        // alg=none style header with an unsigned body must not verify.
        val bad = "${b64("""{"alg":"none","kid":"k1"}""")}.${b64(claims())}."
        val (_, jwks) = mint(claims())
        assertNull(validateIdToken(bad, "oaiapp_x", "n123", jwks, nowMs))
    }

    @Test fun audArrayAccepted() {
        val (token, jwks) = mint(claims(aud = "[\"oaiapp_x\"]"))
        assertEquals("user_1", validateIdToken(token, "oaiapp_x", "n123", jwks, nowMs))
    }

    @Test fun idTokenEmailExtracted() {
        val (token, _) = mint(claims())
        assertEquals("a@b.c", idTokenEmail(token))
        assertNull(idTokenEmail("garbage"))
    }
}
