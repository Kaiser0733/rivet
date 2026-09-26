package com.kaiser.rivet.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTest {
    @Test fun anthropicCachedInputCountsTowardPromptOccupancy() {
        val response = Json.parseToJsonElement("""{"usage":{"input_tokens":20,"output_tokens":5,"cache_creation_input_tokens":30,"cache_read_input_tokens":70}}""").jsonObject
        val usage = anthropicUsage(response)!!
        assertEquals(120L, usage.inputTokens)
        assertEquals(30L, usage.cacheCreationTokens)
        assertEquals(70L, usage.cacheReadTokens)
        assertEquals(5L, usage.outputTokens)
    }
}
