package com.kaiser.rivet.storage

import com.kaiser.rivet.agent.AgentMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal data class EncodedAgentSession(
    val value: String,
    val byteSize: Int,
)

class AgentSessionLimitException(val actualBytes: Int) : Exception(
    "Agent session is $actualBytes bytes; the limit is ${AgentSessionCodec.MAX_SERIALIZED_BYTES} bytes.",
)

internal object AgentSessionCodec {
    // Preferences DataStore remains appropriate while its single transcript
    // value is kept well below a megabyte and rejected before an edit begins.
    const val MAX_SERIALIZED_BYTES = 512 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AgentMessage.serializer())

    fun encode(messages: List<AgentMessage>): EncodedAgentSession {
        val value = json.encodeToString(serializer, messages)
        val byteSize = value.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_SERIALIZED_BYTES) throw AgentSessionLimitException(byteSize)
        return EncodedAgentSession(value, byteSize)
    }

    fun fits(messages: List<AgentMessage>, reservedEncodedBytes: Int = 0): Boolean {
        require(reservedEncodedBytes >= 0)
        val value = json.encodeToString(serializer, messages)
        return value.toByteArray(Charsets.UTF_8).size.toLong() + reservedEncodedBytes <= MAX_SERIALIZED_BYTES
    }

    fun decode(raw: String?): List<AgentMessage> {
        if (raw == null) return emptyList()
        val byteSize = raw.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_SERIALIZED_BYTES) throw AgentSessionLimitException(byteSize)
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: SerializationException) {
            emptyList()
        }
    }
}
