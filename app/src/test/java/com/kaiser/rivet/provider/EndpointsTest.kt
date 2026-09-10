package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointsTest {

    @Test
    fun openAiEndpoints() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            Endpoints.openAiChat("https://api.example.com/v1"),
        )
        assertEquals(
            "https://api.example.com/v1/models",
            Endpoints.openAiModels("https://api.example.com/v1/"),
        )
        // A base URL that already includes the chat path is not doubled.
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            Endpoints.openAiChat("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun anthropicEndpoints() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            Endpoints.anthropicChat("https://api.anthropic.com"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            Endpoints.anthropicChat("https://api.anthropic.com/v1"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            Endpoints.anthropicModels("https://api.anthropic.com/v1"),
        )
    }

    @Test
    fun geminiEndpoints() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse",
            Endpoints.geminiChat(
                "https://generativelanguage.googleapis.com",
                "gemini-2.0-flash",
            ),
        )
        // The models/ prefix is stripped, never doubled.
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse",
            Endpoints.geminiChat(
                "https://generativelanguage.googleapis.com/v1beta",
                "models/gemini-2.0-flash",
            ),
        )
    }
}
