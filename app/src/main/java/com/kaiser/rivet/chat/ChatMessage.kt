package com.kaiser.rivet.chat

import kotlinx.serialization.Serializable

@Serializable
enum class ChatRole {
    User, Assistant;

    val wireName: String get() = name.lowercase()
}

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val text: String,
)
