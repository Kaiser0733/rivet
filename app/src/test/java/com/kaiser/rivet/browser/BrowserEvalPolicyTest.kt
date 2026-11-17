package com.kaiser.rivet.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEvalPolicyTest {
    @Test fun originsNormalizeSchemeHostAndEffectivePort() {
        assertEquals("https://example.com", BrowserEvalPolicy.originOf("HTTPS://Example.COM:443/path?q=1"))
        assertEquals("http://example.com", BrowserEvalPolicy.originOf("http://example.com:80/"))
        assertEquals("https://example.com:8443",
            BrowserEvalPolicy.originOf("https://example.com:8443/a"))
        assertEquals("http://127.0.0.1:45179",
            BrowserEvalPolicy.originOf("http://127.0.0.1:45179/index.html"))
        assertEquals("http://[::1]:8080", BrowserEvalPolicy.originOf("http://[::1]:8080/"))
        assertNull(BrowserEvalPolicy.originOf("file:///data/local/tmp/x.html"))
        assertNull(BrowserEvalPolicy.originOf("javascript:alert(1)"))
        assertNull(BrowserEvalPolicy.originOf("http://user:pass@example.com/"))
    }

    @Test fun sameOriginRejectsDifferentPortsAndSchemes() {
        assertTrue(BrowserEvalPolicy.sameOrigin(
            "http://127.0.0.1:45179/a", "http://127.0.0.1:45179/b"))
        assertFalse(BrowserEvalPolicy.sameOrigin(
            "http://127.0.0.1:45179/", "http://127.0.0.1:45180/"))
        assertFalse(BrowserEvalPolicy.sameOrigin(
            "http://example.com/", "https://example.com/"))
        assertFalse(BrowserEvalPolicy.sameOrigin("not a url", "http://example.com/"))
    }

    @Test fun wrappedScriptSupportsExpressionsAndStatementsAndEscapesSource() {
        val expression = BrowserEvalPolicy.wrapScript("score.textContent")
        assertTrue(expression.contains("new Function('return ('"))
        assertTrue(expression.contains("new Function(__src)"))
        assertTrue(expression.contains("score.textContent"))

        val statement = BrowserEvalPolicy.wrapScript("const a = 1; return a + 1;")
        assertTrue(statement.contains("const a = 1; return a + 1;"))

        val quoted = BrowserEvalPolicy.wrapScript("""({quote: "\"", slash: "\\"})""")
        assertTrue(quoted.contains("quote"))
        assertTrue(quoted.contains("\\\\"))
    }

    @Test fun scriptHarnessHandlesDomFunctionsCircularityAndExceptions() {
        val script = BrowserEvalPolicy.wrapScript("document.body")
        assertTrue(script.contains("nodeType === 1"))
        assertTrue(script.contains("[circular]"))
        assertTrue(script.contains("[function]"))
        assertTrue(script.contains("__e.stack"))
        assertTrue(script.contains("truncated"))
    }

    @Test fun rejectedInputsNameTheLimits() {
        assertTrue(runCatching { BrowserEvalPolicy.wrapScript(" ") }.exceptionOrNull()
            is IllegalArgumentException)
        val tooLong = runCatching { BrowserEvalPolicy.wrapScript("x".repeat(8_193)) }
            .exceptionOrNull()
        assertTrue(tooLong is IllegalArgumentException)
        assertTrue(tooLong!!.message!!.contains("8192"))
        val tooSmall = runCatching { BrowserEvalPolicy.wrapScript("1", maxChars = 10) }
            .exceptionOrNull()
        assertTrue(tooSmall is IllegalArgumentException)
    }

    @Test fun resultBoundingKeepsValidJsonAndMarksTruncation() {
        val small = """{"ok":true,"value":"fine"}"""
        val untouched = BrowserEvalPolicy.boundJson(small)
        assertEquals(small, untouched.json)
        assertFalse(untouched.truncated)

        val large = JsonObject(mapOf(
            "ok" to JsonPrimitive(true),
            "value" to JsonPrimitive("x".repeat(50_000)),
            "truncated" to JsonPrimitive(false),
        )).toString()
        val bounded = BrowserEvalPolicy.boundJson(large, maxBytes = 2_048)
        assertTrue(bounded.truncated)
        assertTrue(bounded.json.toByteArray(Charsets.UTF_8).size <= 2_048)
        val parsed = Json.parseToJsonElement(bounded.json).jsonObject
        assertTrue(parsed["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(parsed["value"]!!.jsonPrimitive.content.length < 50_000)
    }

    @Test fun malformedOversizeResultIsStillBounded() {
        val malformed = "not json".repeat(2_000)
        val bounded = BrowserEvalPolicy.boundJson(malformed, maxBytes = 512)
        assertTrue(bounded.truncated)
        assertTrue(bounded.json.toByteArray(Charsets.UTF_8).size <= 512)
    }
}
