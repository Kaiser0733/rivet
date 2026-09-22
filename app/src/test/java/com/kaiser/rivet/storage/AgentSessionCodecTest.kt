package com.kaiser.rivet.storage

import com.kaiser.rivet.agent.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionCodecTest {
    @Test
    fun encodedUtf8RepresentationWithinLimitRoundTrips() {
        val messages = listOf(AgentMessage.user("hello 🙂"))

        val encoded = AgentSessionCodec.encode(messages)

        assertEquals(encoded.value.toByteArray(Charsets.UTF_8).size, encoded.byteSize)
        assertTrue(encoded.byteSize < AgentSessionCodec.MAX_SERIALIZED_BYTES)
        assertEquals(messages, AgentSessionCodec.decode(encoded.value))
    }

    @Test
    fun encodedUtf8RepresentationAboveLimitIsRejected() {
        val messages = listOf(AgentMessage.user("🙂".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES)))

        val failure = org.junit.Assert.assertThrows(AgentSessionLimitException::class.java) {
            AgentSessionCodec.encode(messages)
        }

        assertTrue(failure.actualBytes > AgentSessionCodec.MAX_SERIALIZED_BYTES)
    }

    @Test
    fun previouslyPersistedOversizedEncodingIsRejectedOnDecode() {
        val raw = "[\"" + "x".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES) + "\"]"

        val failure = org.junit.Assert.assertThrows(AgentSessionLimitException::class.java) {
            AgentSessionCodec.decode(raw)
        }

        assertTrue(failure.actualBytes > AgentSessionCodec.MAX_SERIALIZED_BYTES)
    }
}
