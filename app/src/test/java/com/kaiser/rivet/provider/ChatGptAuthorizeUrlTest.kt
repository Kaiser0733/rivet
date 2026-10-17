package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class ChatGptAuthorizeUrlTest {

    private fun params(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        return query.split('&').associate { pair ->
            val idx = pair.indexOf('=')
            URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }
    }

    @Test fun dynamicClientUrl() {
        val url = buildAuthorizeUrl(
            clientId = "dynamic_agent_client",
            hostId = "host-123",
            port = 54321,
            state = "s",
            nonce = "n",
            challenge = "c",
        )
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        val p = params(url)
        assertEquals("dynamic_agent_client", p["client_id"])
        assertEquals("Rivet", p["agent_name_hint"])
        assertEquals("host-123", p["ext_agent_host_id"])
        assertEquals("code", p["response_type"])
        assertEquals("http://127.0.0.1:54321/auth/callback", p["redirect_uri"])
        assertEquals(
            "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            p["scope"],
        )
        assertEquals("https://api.openai.com/v1", p["resource"])
        assertEquals("s", p["state"])
        assertEquals("n", p["nonce"])
        assertEquals("c", p["code_challenge"])
        assertEquals("S256", p["code_challenge_method"])
    }

    @Test fun reauthOmitsAgentNameHint() {
        val url = buildAuthorizeUrl(
            clientId = "oaiapp_abc123",
            hostId = "host-123",
            port = 1,
            state = "s",
            nonce = "n",
            challenge = "c",
        )
        val p = params(url)
        assertEquals("oaiapp_abc123", p["client_id"])
        assertFalse("agent_name_hint" in p)
    }

    @Test fun redirectNeverUsesLocalhost() {
        val url = buildAuthorizeUrl("dynamic_agent_client", "h", 9999, "s", "n", "c")
        assertFalse("localhost" in url)
        assertTrue("http://127.0.0.1:9999/auth/callback" in params(url)["redirect_uri"].orEmpty())
    }
}
