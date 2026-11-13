package com.kaiser.rivet.provider

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptRefreshPolicyTest {

    @Test
    fun refreshResponseAllowsOmittedIdTokenWhileCodeExchangeRequiresIt() {
        val body = """{"access_token":"new-acc","refresh_token":"new-ref","expires_in":1800}"""
        assertTrue(parseTokenResponse(body, requireIdToken = true) is TokenResult.Failed)

        val refresh = parseTokenResponse(body, requireIdToken = false)
        assertTrue(refresh is TokenResult.Ok)
        val ok = refresh as TokenResult.Ok
        assertEquals("new-acc", ok.accessToken)
        assertEquals("new-ref", ok.refreshToken)
        assertEquals(null, ok.idToken)
    }

    @Test
    fun mergeRefreshedBundleFieldsPreservesScopesAndUpdatesEmailOnRotatedIdToken() {
        val tokensWithoutScopeOrId = TokenResult.Ok(
            accessToken = "acc2",
            refreshToken = "ref2",
            idToken = null,
            expiresInSec = 900L,
            scope = "",
        )
        val kept = mergeRefreshedBundleFields(
            currentIdToken = "idt1",
            currentScopes = listOf("openid", "chatgpt.tokens.use.direct"),
            currentEmail = "old@example.com",
            tokens = tokensWithoutScopeOrId,
            extractEmail = { "new@example.com" },
        )
        assertEquals(listOf("openid", "chatgpt.tokens.use.direct"), kept.scopes)
        assertEquals("idt1", kept.idToken)
        assertEquals("old@example.com", kept.email)

        val rotated = mergeRefreshedBundleFields(
            currentIdToken = "idt1",
            currentScopes = listOf("openid", "chatgpt.tokens.use.direct"),
            currentEmail = "old@example.com",
            tokens = tokensWithoutScopeOrId.copy(
                idToken = "idt2",
                scope = "openid profile chatgpt.tokens.use.direct",
            ),
            extractEmail = { token -> if (token == "idt2") "rotated@example.com" else null },
        )
        assertEquals("idt2", rotated.idToken)
        assertEquals("rotated@example.com", rotated.email)
        assertEquals(listOf("openid", "profile", "chatgpt.tokens.use.direct"), rotated.scopes)
    }

    @Test
    fun classifyRefreshFailureSeparatesDeadTokensFromTransientErrors() {
        assertEquals(
            ChatGptRefreshFailure.REAUTH_REQUIRED,
            classifyRefreshFailure(TokenResult.Failed("invalid_grant")),
        )
        assertEquals(
            ChatGptRefreshFailure.TRANSIENT,
            classifyRefreshFailure(TokenResult.NetworkFailed),
        )
        assertEquals(
            ChatGptRefreshFailure.TRANSIENT,
            classifyRefreshFailure(TokenResult.Failed("server_error")),
        )
    }

    @Test
    fun sharedBundleCodecIgnoresUnknownKeys() {
        val raw = """{"clientId":"c1","unknown_future_field":123}"""
        val obj = chatGptBundleJson.parseToJsonElement(raw).jsonObject
        assertEquals("c1", obj["clientId"]?.jsonPrimitive?.content)
    }
}
