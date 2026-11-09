package com.kaiser.rivet.storage

import android.content.Context
import com.kaiser.rivet.agent.GoalDocument
import kotlinx.serialization.json.Json

/** No-project goals belong to one conversation, not to a folder; they die with it. */
internal class SessionGoalStore(context: Context) {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val preferences by lazy {
        app.getSharedPreferences("session_goals", Context.MODE_PRIVATE)
    }

    suspend fun load(sessionId: String?): GoalDocument? {
        val key = sessionId ?: return null
        val raw = preferences.getString(key, null) ?: return null
        return try { json.decodeFromString(GoalDocument.serializer(), raw) }
        catch (_: Exception) { null }
    }

    suspend fun save(sessionId: String?, goal: GoalDocument?) {
        val key = sessionId ?: return
        preferences.edit().apply {
            if (goal == null) remove(key)
            else putString(key, json.encodeToString(GoalDocument.serializer(), goal))
        }.apply()
    }
}
