package com.kaiser.rivet.provider

import org.junit.Assert.assertTrue
import org.junit.Test

class HttpErrorTest {

    @Test
    fun openAiStyleModelNotFound() {
        val e = httpError(
            400,
            """{"error":{"message":"The model 'gpt-x' does not exist or you do not have access to it."}}""",
        )
        assertTrue(e is ProviderError.ModelNotFound)
        assertTrue(e.text().contains("gpt-x"))
    }

    @Test
    fun anthropicStyleModelNotFound() {
        val e = httpError(404, """{"type":"error","error":{"message":"model: claude-x"}}""")
        assertTrue(e.text().isNotEmpty())
    }

    @Test
    fun plainMessageBodyIsRetainedWithoutShowingItInChat() {
        val e = httpError(400, """{"message":"max_tokens is required"}""")
        assertTrue(e is ProviderError.ProviderMessage)
        assertTrue((e as ProviderError.ProviderMessage).text.contains("max_tokens"))
        assertTrue(!e.text().contains("max_tokens"))
    }

    @Test
    fun noBodyFallsBackToStatusText() {
        val e = httpError(418, null)
        assertTrue(e is ProviderError.ProviderMessage)
        assertTrue((e as ProviderError.ProviderMessage).text.contains("418"))
    }
}
