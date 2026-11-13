package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ChatGptIdTokenValidatorTest {

    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val nowMs = 1_700_000_000_000L
    private val issuer = "https://auth.openai.com"

    private fun b64(obj: String) = enc.encodeToString(obj.toByteArray(Charsets.UTF_8))

    private fun mint(
        claims: String,
        kid: String = "k1",
        jwkExtra: String = "\"kty\":\"RSA\"",
    ): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val rsaPub = pair.public as java.security.interfaces.RSAPublicKey
        val header = b64("""{"alg":"RS256","kid":"$kid","typ":"JWT"}""")
        val payload = b64(claims)
        val signingInput = "$header.$payload".toByteArray(Charsets.US_ASCII)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(pair.private)
        sig.update(signingInput)
        val sigBytes = sig.sign()
        val token = "$header.$payload.${enc.encodeToString(sigBytes)}"

        fun strip(v: BigInteger) = enc.encodeToString(v.toByteArray().let {
            if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        })
        val jwks = """{"keys":[{$jwkExtra,"kid":"$kid","n":"${strip(rsaPub.modulus)}","e":"${strip(rsaPub.publicExponent)}"}]}"""
        return token to jwks
    }

    @Test fun rejectsMixedTypeAudArray() {
        val (token, jwks) = mint(
            """{"iss":"$issuer","aud":["oaiapp_x",123],"exp":2000000000,"nonce":"n123","sub":"user_1"}"""
        )
        assertNull(ChatGptIdTokenValidator.validateIdToken(token, issuer, "oaiapp_x", "n123", jwks, nowMs))
    }

    @Test fun rejectsEmptyOrBlankSub() {
        val (emptySub, jwks1) = mint(
            """{"iss":"$issuer","aud":"oaiapp_x","exp":2000000000,"nonce":"n123","sub":""}"""
        )
        assertNull(ChatGptIdTokenValidator.validateIdToken(emptySub, issuer, "oaiapp_x", "n123", jwks1, nowMs))

        val (blankSub, jwks2) = mint(
            """{"iss":"$issuer","aud":"oaiapp_x","exp":2000000000,"nonce":"n123","sub":"   "}"""
        )
        assertNull(ChatGptIdTokenValidator.validateIdToken(blankSub, issuer, "oaiapp_x", "n123", jwks2, nowMs))
    }

    @Test fun rejectsNonRsaOrNonSigOrWrongAlgJwk() {
        val validClaims = """{"iss":"$issuer","aud":"oaiapp_x","exp":2000000000,"nonce":"n123","sub":"user_1"}"""
        val (tok1, jwksEc) = mint(validClaims, jwkExtra = "\"kty\":\"EC\"")
        assertNull(ChatGptIdTokenValidator.validateIdToken(tok1, issuer, "oaiapp_x", "n123", jwksEc, nowMs))

        val (tok2, jwksEnc) = mint(validClaims, jwkExtra = "\"kty\":\"RSA\",\"use\":\"enc\"")
        assertNull(ChatGptIdTokenValidator.validateIdToken(tok2, issuer, "oaiapp_x", "n123", jwksEnc, nowMs))

        val (tok3, jwksAlg) = mint(validClaims, jwkExtra = "\"kty\":\"RSA\",\"alg\":\"RS384\"")
        assertNull(ChatGptIdTokenValidator.validateIdToken(tok3, issuer, "oaiapp_x", "n123", jwksAlg, nowMs))

        val (tok4, jwksOk) = mint(validClaims, jwkExtra = "\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\"")
        assertEquals("user_1", ChatGptIdTokenValidator.validateIdToken(tok4, issuer, "oaiapp_x", "n123", jwksOk, nowMs))
    }

    @Test fun discoveryEndpointsPinnedToHttpsAndExpectedDomain() {
        assertTrue(ChatGptIdTokenValidator.isPinnedHttpsEndpoint("https://auth.openai.com/.well-known/jwks.json"))
        assertTrue(ChatGptIdTokenValidator.isPinnedHttpsEndpoint("https://cdn.openai.com/oauth/revoke"))
        assertFalse(ChatGptIdTokenValidator.isPinnedHttpsEndpoint("http://auth.openai.com/.well-known/jwks.json"))
        assertFalse(ChatGptIdTokenValidator.isPinnedHttpsEndpoint("https://evil.example/jwks.json"))
        assertFalse(ChatGptIdTokenValidator.isPinnedHttpsEndpoint("https://notopenai.com/jwks.json"))
        assertFalse(ChatGptIdTokenValidator.isPinnedHttpsEndpoint("https://user@auth.openai.com/jwks.json"))
        assertFalse(ChatGptIdTokenValidator.isPinnedHttpsEndpoint("https://auth.openai.com:8443/jwks.json"))
    }
}
