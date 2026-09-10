package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.rivet.chat.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.chatData: DataStore<Preferences> by preferencesDataStore("chat")

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
