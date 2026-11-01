package com.kaiser.rivet.provider

import org.junit.Assert.assertFalse
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
        assertTrue(e is ProviderError.ModelNotFound)
        assertTrue((e as ProviderError.ModelNotFound).model.contains("claude-x"))
    }

    // A base URL without /v1 answers 404 with a body that mentions "models". That is a path
    // problem, not a missing model, and it must not quote the sentence as a model name.
    @Test
    fun aMissingPathIsNotAMissingModel() {
        val e = httpError(404, """{"error":{"message":"Invalid URL (GET /models)"}}""")
        assertFalse(e is ProviderError.ModelNotFound)
        assertFalse(e.text().contains("was not found by this provider"))
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
