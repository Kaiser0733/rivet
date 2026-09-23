package com.kaiser.rivet.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.workspace.WorkspaceSelection
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class CodingSessionHeader(
    val id: String,
    val title: String,
    val workspaceId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val interrupted: Boolean,
)

data class SessionUsage(
    val reportedInputTokens: Long,
    val reportedOutputTokens: Long,
    val reportedRequests: Int,
    val unknownRequests: Int,
    val latestInputTokens: Long?,
    val latestSource: String?,
)

/** Provider-neutral event rows. The old DataStore value is retained after migration. */
internal class CodingSessions(private val context: Context) : AgentSessionPersistence {
    private val database = SessionDatabase(context)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var initialized = false

    override suspend fun load(): AgentSession = onDatabase { db ->
        val id = activeId(db)
        val header = header(db, id)
        AgentSession(messages(db, id), header.interrupted, header.id, header.title, header.workspaceId)
    }

    override suspend fun save(messages: List<AgentMessage>, interrupted: Boolean) = onDatabase { db ->
        val id = activeId(db)
        val count = db.rawQuery("SELECT COUNT(*) FROM events WHERE session_id=?", arrayOf(id)).use {
            it.moveToFirst(); it.getInt(0)
        }
        val tail = db.rawQuery("SELECT id,payload FROM events WHERE session_id=? ORDER BY id DESC LIMIT 2", arrayOf(id)).use {
            buildList { while (it.moveToNext()) add(it.getLong(0) to decode(it.getString(1))) }
        }
        val last = tail.firstOrNull()?.second
        val retractEmpty = messages.size == count - 1 && last?.role == AgentRole.Assistant &&
            last.text.isBlank() && last.toolCalls.isEmpty() &&
            (count == 1 || messages.lastOrNull() == tail.getOrNull(1)?.second)
        if (!retractEmpty && (messages.size < count || (count > 0 && messages[count - 1] != last))) {
            throw IllegalStateException("Session event prefix changed")
        }
        // Until context compaction lands, the active model transcript keeps the
        // Phase 4 limit. The database's complete event history has no such cap.
        AgentSessionCodec.encode(messages)
        db.beginTransaction()
        try {
            if (retractEmpty) db.delete("events", "id=?", arrayOf(tail.first().first.toString()))
            messages.drop(count).forEach { message ->
                db.insertOrThrow("events", null, ContentValues().apply {
                    put("session_id", id)
                    put("payload", json.encodeToString(AgentMessage.serializer(), message))
                })
            }
            db.execSQL("UPDATE sessions SET interrupted=?, updated_at=? WHERE id=?",
                arrayOf(if (interrupted) 1 else 0, System.currentTimeMillis(), id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun canSaveWithReserve(messages: List<AgentMessage>, reservedEncodedBytes: Int): Boolean =
        AgentSessionCodec.fits(messages, reservedEncodedBytes)

    override suspend fun markInterrupted(interrupted: Boolean) = onDatabase { db ->
        db.execSQL("UPDATE sessions SET interrupted=? WHERE id=?",
            arrayOf(if (interrupted) 1 else 0, activeId(db)))
    }

    override suspend fun clear() = onDatabase { db ->
        val id = activeId(db)
        db.beginTransaction()
        try {
            db.delete("events", "session_id=?", arrayOf(id))
            db.execSQL("UPDATE sessions SET interrupted=0, updated_at=? WHERE id=?",
                arrayOf(System.currentTimeMillis(), id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun list(): List<CodingSessionHeader> = onDatabase { db ->
        db.rawQuery("SELECT id,title,workspace_id,created_at,updated_at,interrupted FROM sessions ORDER BY updated_at DESC", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.header()) } }
    }

    suspend fun create(workspaceId: String?): AgentSession = onDatabase { db ->
        val id = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            insertSession(db, id, "New session", workspaceId, false)
            setActive(db, id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        AgentSession(id = id, title = "New session", workspaceId = workspaceId)
    }

    suspend fun select(id: String): AgentSession = onDatabase { db ->
        val selected = header(db, id)
        setActive(db, id)
        AgentSession(messages(db, id), selected.interrupted, id, selected.title, selected.workspaceId)
    }

    suspend fun rename(id: String, title: String) = onDatabase { db ->
        val clean = title.trim().take(80)
        require(clean.isNotEmpty())
        if (db.update("sessions", ContentValues().apply { put("title", clean) },
                "id=?", arrayOf(id)) != 1) throw IllegalArgumentException("Unknown session")
    }

    suspend fun delete(id: String): AgentSession = onDatabase { db ->
        val workspaceId = WorkspaceSelection(context).currentIdentity()
        db.beginTransaction()
        try {
            if (db.delete("sessions", "id=?", arrayOf(id)) != 1) throw IllegalArgumentException("Unknown session")
            val next = db.rawQuery("SELECT id FROM sessions ORDER BY updated_at DESC LIMIT 1", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: UUID.randomUUID().toString().also {
                insertSession(db, it, "New session", workspaceId, false)
            }
            if (activeId(db) == id) setActive(db, next)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val active = activeId(db)
        val selected = header(db, active)
        AgentSession(messages(db, active), selected.interrupted, active, selected.title, selected.workspaceId)
    }

    suspend fun recent(id: String, limit: Int = 100): List<AgentMessage> = onDatabase { db ->
        require(limit in 1..500)
        db.rawQuery("SELECT payload FROM events WHERE session_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(id, limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }.asReversed()
        }
    }

    suspend fun recordUsage(sessionId: String, turnId: String, providerId: String, model: String,
                            usage: AgentUsage?) = onDatabase { db ->
        db.insertOrThrow("usage", null, ContentValues().apply {
            put("session_id", sessionId); put("turn_id", turnId)
            put("provider_id", providerId); put("model", model)
            put("source", if (usage == null) "unknown" else "reported")
            put("input_tokens", usage?.inputTokens); put("output_tokens", usage?.outputTokens)
            put("cache_read_tokens", usage?.cacheReadTokens)
            put("reasoning_tokens", usage?.reasoningTokens)
            put("total_tokens", usage?.totalTokens)
            put("created_at", System.currentTimeMillis())
        })
    }

    suspend fun usage(sessionId: String): SessionUsage = onDatabase { db ->
        val totals = db.rawQuery("SELECT COALESCE(SUM(input_tokens),0),COALESCE(SUM(output_tokens),0)," +
            "SUM(CASE WHEN source='reported' THEN 1 ELSE 0 END)," +
            "SUM(CASE WHEN source='unknown' THEN 1 ELSE 0 END) FROM usage WHERE session_id=?",
            arrayOf(sessionId)).use { cursor ->
            cursor.moveToFirst()
            listOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3))
        }
        val latest = db.rawQuery("SELECT input_tokens,source FROM usage WHERE session_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(sessionId)).use { cursor ->
            if (cursor.moveToFirst()) (if (cursor.isNull(0)) null else cursor.getLong(0)) to cursor.getString(1)
            else null to null
        }
        SessionUsage(totals[0], totals[1], totals[2].toInt(), totals[3].toInt(), latest.first, latest.second)
    }

    private suspend fun <T> onDatabase(block: suspend (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = database.writableDatabase
            if (!initialized) {
                migrate(db)
                initialized = true
            }
            block(db)
        }
    }

    private suspend fun migrate(db: SQLiteDatabase) {
        if (meta(db, "legacy_migrated") != null) return
        val legacy = AgentSessionStore(context).load()
        val workspaceId = WorkspaceSelection(context).currentIdentity()
        val id = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            if (meta(db, "legacy_migrated") == null) {
                insertSession(db, id, "Previous conversation", workspaceId, legacy.interrupted)
                legacy.messages.forEach { message ->
                    db.insertOrThrow("events", null, ContentValues().apply {
                        put("session_id", id)
                        put("payload", json.encodeToString(AgentMessage.serializer(), message))
                    })
                }
                putMeta(db, "active_session", id)
                putMeta(db, "legacy_migrated", "1")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun activeId(db: SQLiteDatabase): String = meta(db, "active_session")
        ?: throw IllegalStateException("No active session")

    private fun messages(db: SQLiteDatabase, id: String): List<AgentMessage> =
        db.rawQuery("SELECT payload FROM events WHERE session_id=? ORDER BY id", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }
        }

    private fun decode(payload: String): AgentMessage =
        json.decodeFromString(AgentMessage.serializer(), payload)

    private fun header(db: SQLiteDatabase, id: String): CodingSessionHeader =
        db.rawQuery("SELECT id,title,workspace_id,created_at,updated_at,interrupted FROM sessions WHERE id=?", arrayOf(id))
            .use { cursor ->
                if (!cursor.moveToFirst()) throw IllegalArgumentException("Unknown session")
                cursor.header()
            }

    private fun Cursor.header() = CodingSessionHeader(getString(0), getString(1),
        if (isNull(2)) null else getString(2), getLong(3), getLong(4), getInt(5) != 0)

    private fun insertSession(db: SQLiteDatabase, id: String, title: String, workspaceId: String?, interrupted: Boolean) {
        val now = System.currentTimeMillis()
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("title", title); put("workspace_id", workspaceId)
            put("created_at", now); put("updated_at", now); put("interrupted", if (interrupted) 1 else 0)
        })
    }

    private fun setActive(db: SQLiteDatabase, id: String) = putMeta(db, "active_session", id)

    private fun meta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

private class SessionDatabase(context: Context) : SQLiteOpenHelper(context, "coding-sessions.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, workspace_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, interrupted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX events_session_id ON events(session_id,id)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createUsage(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) createUsage(db)
        else throw IllegalStateException("Unsupported session database upgrade $oldVersion to $newVersion")
    }

    private fun createUsage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usage (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, turn_id TEXT NOT NULL, provider_id TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER, cache_read_tokens INTEGER, reasoning_tokens INTEGER, total_tokens INTEGER, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX usage_session_id ON usage(session_id,id)")
    }
}
