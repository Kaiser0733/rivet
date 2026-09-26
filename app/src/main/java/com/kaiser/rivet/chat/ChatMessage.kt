package com.kaiser.rivet.chat

import kotlinx.serialization.Serializable

@Serializable
internal enum class ChatRole { User, Assistant }

// Historical DataStore payload only; CodingSessions imports it once into agent events.
@Serializable
internal data class ChatMessage(
    val role: ChatRole,
    val text: String,
)
