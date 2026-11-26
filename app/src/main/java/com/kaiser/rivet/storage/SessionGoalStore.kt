package com.kaiser.rivet.storage

import android.content.Context
import com.kaiser.rivet.agent.GoalDocument
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Conversation and project goals are owned by the app. */
internal class SessionGoalStore(context: Context) {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val preferences by lazy {
        app.getSharedPreferences("session_goals", Context.MODE_PRIVATE)
    }

    private val projectPreferences by lazy {
        app.getSharedPreferences("project_goals", Context.MODE_PRIVATE)
    }

    suspend fun loadProject(workspaceId: String?, legacy: suspend () -> GoalDocument?): GoalDocument? {
        val key = workspaceId ?: return null
        return withContext(Dispatchers.IO) {
            projectLock.withLock {
                val stored = projectPreferences.getString(key, null)
                if (stored != null) return@withLock decode(stored)
                legacy().also { saveProjectLocked(key, it) }
            }
        }
    }

    suspend fun saveProject(workspaceId: String, goal: GoalDocument) = withContext(Dispatchers.IO) {
        projectLock.withLock { saveProjectLocked(workspaceId, goal) }
    }

    private fun saveProjectLocked(workspaceId: String, goal: GoalDocument?) {
        val encoded = goal?.let { json.encodeToString(GoalDocument.serializer(), it) }.orEmpty()
        if (!projectPreferences.edit().putString(workspaceId, encoded).commit()) {
            throw java.io.IOException("Could not save the project goal")
        }
    }

    private fun decode(raw: String): GoalDocument? = try { json.decodeFromString(GoalDocument.serializer(), raw) }
        catch (_: IllegalArgumentException) { null }

    private companion object { val projectLock = Mutex() }

    suspend fun load(sessionId: String?): GoalDocument? {
        val key = sessionId ?: return null
        val raw = preferences.getString(key, null) ?: return null
        return decode(raw)
    }

    suspend fun save(sessionId: String?, goal: GoalDocument?) {
        val key = sessionId ?: return
        preferences.edit().apply {
            if (goal == null) remove(key)
            else putString(key, json.encodeToString(GoalDocument.serializer(), goal))
        }.apply()
    }
}
