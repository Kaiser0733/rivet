package com.kaiser.rivet.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal object ChatGptIdTokenValidator {

    private fun b64UrlDecode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)

    fun isPinnedHttpsEndpoint(url: String, expectedDomain: String = "openai.com"): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.rawUserInfo.isNullOrEmpty()) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        return host.equals(expectedDomain, ignoreCase = true) ||
            host.endsWith(".$expectedDomain", ignoreCase = true)
    }

    fun validateIdToken(
        idToken: String,
        expectedIssuer: String,
        expectedClientId: String,
        expectedNonce: String,
        jwksJson: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val parts = idToken.split('.')
        if (parts.size != 3) return null
        val header = try {
            parseJsonObject(String(b64UrlDecode(parts[0]), Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        if (header["alg"]?.str() != "RS256") return null
        val kid = header["kid"]?.str() ?: return null

        val jwk = parseJsonObject(jwksJson)?.get("keys")?.let { keys ->
            (keys as? JsonArray)
                ?.mapNotNull { it.obj() }
                ?.firstOrNull { it["kid"]?.str() == kid }
        } ?: return null
        if (jwk["kty"]?.str() != "RSA") return null
        if (jwk["use"]?.str()?.let { it != "sig" } == true) return null
        if (jwk["alg"]?.str()?.let { it != "RS256" } == true) return null
        val n = try { b64UrlDecode(jwk["n"]?.str() ?: return null) } catch (e: IllegalArgumentException) { return null }
        val e = try { b64UrlDecode(jwk["e"]?.str() ?: return null) } catch (e: IllegalArgumentException) { return null }

        val signatureValid = try {
            val keySpec = RSAPublicKeySpec(
                java.math.BigInteger(1, n),
                java.math.BigInteger(1, e),
            )
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            sig.verify(b64UrlDecode(parts[2]))
        } catch (ex: Exception) {
            false
        }
        if (!signatureValid) return null

        val claims = try {
            parseJsonObject(String(b64UrlDecode(parts[1]), Charsets.UTF_8))
        } catch (ex: IllegalArgumentException) {
            null
        } ?: return null
        if (claims["iss"]?.str() != expectedIssuer) return null
        val audOk = when (val aud = claims["aud"]) {
            is JsonPrimitive ->
                aud.takeIf { it.isString }?.content == expectedClientId
            is JsonArray ->
                aud.size == 1 && aud[0].str() == expectedClientId
            else -> false
        }
        if (!audOk) return null
        val expSec = (claims["exp"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        if (expSec <= nowMs / 1000 - 60) return null
        if (claims["nonce"]?.str() != expectedNonce) return null
        return claims["sub"]?.str()?.takeIf { it.isNotBlank() }
    }

    fun idTokenEmail(idToken: String): String? {
        val parts = idToken.split('.')
        if (parts.size != 3) return null
        return try {
            parseJsonObject(String(b64UrlDecode(parts[1]), Charsets.UTF_8))
                ?.get("email")?.str()
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
