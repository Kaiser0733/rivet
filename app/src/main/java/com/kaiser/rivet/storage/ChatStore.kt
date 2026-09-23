package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.rivet.chat.ChatMessage
import com.kaiser.rivet.chat.ChatRole
import com.kaiser.rivet.agent.AgentMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal val Context.chatData: DataStore<Preferences> by preferencesDataStore("chat")

// One persistent conversation. Message-level provenance (provider/model)
// is stored per message; nothing here is secret and no API keys ever flow
// into chat storage.
class ChatStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val messagesKey = stringPreferencesKey("messages")

    val messages: Flow<List<ChatMessage>> = context.chatData.data.map { prefs ->
        decode(prefs[messagesKey])
    }

    private fun decode(raw: String?): List<ChatMessage> = try {
        if (raw == null) emptyList()
        else json.decodeFromString(ListSerializer(ChatMessage.serializer()), raw)
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun save(messages: List<ChatMessage>) {
        context.chatData.edit {
            it[messagesKey] = json.encodeToString(ListSerializer(ChatMessage.serializer()), messages)
        }
    }

    suspend fun clear() {
        context.chatData.edit { it.remove(messagesKey) }
    }
}

@Serializable
data class AgentSession(
    val messages: List<AgentMessage> = emptyList(),
    val interrupted: Boolean = false,
    val id: String? = null,
    val title: String? = null,
    val workspaceId: String? = null,
    val summary: String = "",
)

internal interface AgentSessionPersistence {
    suspend fun load(): AgentSession
    suspend fun save(messages: List<AgentMessage>, interrupted: Boolean)
    fun canSaveWithReserve(messages: List<AgentMessage>, reservedEncodedBytes: Int): Boolean
    suspend fun markInterrupted(interrupted: Boolean)
    suspend fun clear()
}

internal class AgentSessionStore(private val context: Context) : AgentSessionPersistence {
    private val json = Json { ignoreUnknownKeys = true }
    private val agentKey = stringPreferencesKey("agent_messages")
    private val interruptedKey = androidx.datastore.preferences.core.booleanPreferencesKey("agent_interrupted")
    private val migratedKey = androidx.datastore.preferences.core.booleanPreferencesKey("agent_migrated")
    private val legacyKey = stringPreferencesKey("messages")

    override suspend fun load(): AgentSession {
        val updated = context.chatData.edit { prefs ->
            if (prefs[agentKey] == null && prefs[migratedKey] != true) {
                val legacy = decodeLegacy(prefs[legacyKey])
                val migrated = legacy.map { message ->
                    AgentMessage(
                        role = if (message.role == ChatRole.User) {
                            com.kaiser.rivet.agent.AgentRole.User
                        } else {
                            com.kaiser.rivet.agent.AgentRole.Assistant
                        },
                        text = message.text,
                    )
                }
                prefs[agentKey] = AgentSessionCodec.encode(migrated).value
                prefs[migratedKey] = true
            }
        }
        return AgentSession(
            messages = AgentSessionCodec.decode(updated[agentKey]),
            interrupted = updated[interruptedKey] == true,
        )
    }

    override suspend fun save(messages: List<AgentMessage>, interrupted: Boolean) {
        val encoded = AgentSessionCodec.encode(messages)
        context.chatData.edit { prefs ->
            prefs[agentKey] = encoded.value
            prefs[interruptedKey] = interrupted
            prefs[migratedKey] = true
        }
    }

    override fun canSaveWithReserve(messages: List<AgentMessage>, reservedEncodedBytes: Int): Boolean =
        AgentSessionCodec.fits(messages, reservedEncodedBytes)

    override suspend fun markInterrupted(interrupted: Boolean) {
        context.chatData.edit { it[interruptedKey] = interrupted }
    }

    override suspend fun clear() {
        context.chatData.edit { prefs ->
            prefs.remove(agentKey)
            prefs.remove(legacyKey)
            prefs.remove(interruptedKey)
            prefs[migratedKey] = true
        }
    }

    private fun decodeLegacy(raw: String?): List<ChatMessage> = try {
        if (raw == null) emptyList()
        else json.decodeFromString(ListSerializer(ChatMessage.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }
}
