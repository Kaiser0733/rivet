package com.kaiser.rivet.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kaiser.rivet.agent.AgentContext
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.workspace.WorkspaceSelection
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

data class ContextEstimate(val tokens: Long?, val source: String)

/** Provider-neutral event rows. The old DataStore value is retained after migration. */
internal class CodingSessions(private val context: Context) : AgentSessionPersistence {
    private val database = SessionDatabase(context)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var initialized = false
    @Volatile private var summaryBytes = 0

    override suspend fun load(): AgentSession = onDatabase { db ->
        val id = activeId(db)
        val header = header(db, id)
        validateProjection(db, id)
        val summary = summary(db, id)
        summaryBytes = summary.toByteArray(Charsets.UTF_8).size
        val messages = recoverInterrupted(db, id, header.interrupted, activeMessages(db, id), summaryBytes)
        AgentSession(messages, header.interrupted, header.id, header.title, header.workspaceId, summary)
    }

    override suspend fun save(messages: List<AgentMessage>, interrupted: Boolean) = onDatabase { db ->
        val id = activeId(db)
        val count = db.rawQuery("SELECT COUNT(*) FROM active_events WHERE session_id=?", arrayOf(id)).use {
            it.moveToFirst(); it.getInt(0)
        }
        val tail = db.rawQuery("SELECT id,payload FROM active_events WHERE session_id=? ORDER BY id DESC LIMIT 2", arrayOf(id)).use {
            buildList { while (it.moveToNext()) add(it.getLong(0) to decode(it.getString(1))) }
        }
        val last = tail.firstOrNull()?.second
        val retractEmpty = messages.size == count - 1 && last?.role == AgentRole.Assistant &&
            last.text.isBlank() && last.toolCalls.isEmpty() &&
            (count == 1 || messages.lastOrNull() == tail.getOrNull(1)?.second)
        if (!retractEmpty && (messages.size < count || (count > 0 && messages[count - 1] != last))) {
            throw IllegalStateException("Session event prefix changed")
        }
        // Only the active model transcript has a size limit; complete event history does not.
        if (!AgentSessionCodec.fits(messages, summary(db, id).toByteArray(Charsets.UTF_8).size)) {
            throw AgentSessionLimitException(AgentSessionCodec.MAX_SERIALIZED_BYTES + 1)
        }
        db.beginTransaction()
        try {
            if (retractEmpty) {
                db.delete("active_events", "id=?", arrayOf(tail.first().first.toString()))
                db.execSQL("DELETE FROM events WHERE id=(SELECT MAX(id) FROM events WHERE session_id=?)", arrayOf(id))
            }
            messages.drop(count).forEach { message ->
                val payload = json.encodeToString(AgentMessage.serializer(), message)
                db.insertOrThrow("events", null, ContentValues().apply {
                    put("session_id", id)
                    put("payload", payload)
                })
                db.insertOrThrow("active_events", null, ContentValues().apply {
                    put("session_id", id); put("payload", payload)
                })
            }
            db.execSQL("UPDATE sessions SET interrupted=?, updated_at=? WHERE id=?",
                arrayOf(if (interrupted) 1 else 0, System.currentTimeMillis(), id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun canSaveWithReserve(messages: List<AgentMessage>, reservedEncodedBytes: Int): Boolean =
        AgentSessionCodec.fits(messages, reservedEncodedBytes + summaryBytes)

    override suspend fun markInterrupted(interrupted: Boolean) = onDatabase { db ->
        db.execSQL("UPDATE sessions SET interrupted=? WHERE id=?",
            arrayOf(if (interrupted) 1 else 0, activeId(db)))
    }

    override suspend fun clear() = onDatabase { db ->
        val id = activeId(db)
        db.beginTransaction()
        try {
            db.delete("events", "session_id=?", arrayOf(id))
            db.delete("active_events", "session_id=?", arrayOf(id))
            db.delete("compactions", "session_id=?", arrayOf(id))
            db.delete("usage", "session_id=?", arrayOf(id))
            db.execSQL("UPDATE sessions SET interrupted=0, summary='', active_generation=active_generation+1, updated_at=? WHERE id=?",
                arrayOf(System.currentTimeMillis(), id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        summaryBytes = 0
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
        summaryBytes = 0
        AgentSession(id = id, title = "New session", workspaceId = workspaceId)
    }

    suspend fun select(id: String): AgentSession = onDatabase { db ->
        val selected = header(db, id)
        validateProjection(db, id)
        val summary = summary(db, id)
        val messages = recoverInterrupted(db, id, selected.interrupted, activeMessages(db, id),
            summary.toByteArray(Charsets.UTF_8).size)
        setActive(db, id)
        summaryBytes = summary.toByteArray(Charsets.UTF_8).size
        AgentSession(messages, selected.interrupted, id, selected.title, selected.workspaceId, summary)
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
        val summary = summary(db, active)
        summaryBytes = summary.toByteArray(Charsets.UTF_8).size
        AgentSession(activeMessages(db, active), selected.interrupted, active, selected.title, selected.workspaceId, summary)
    }

    suspend fun recent(id: String, limit: Int = 100): List<AgentMessage> = onDatabase { db ->
        require(limit in 1..500)
        db.rawQuery("SELECT payload FROM events WHERE session_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(id, limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }.asReversed()
        }
    }

    suspend fun compact(expected: List<AgentMessage>, retained: List<AgentMessage>, summary: String) = onDatabase { db ->
        val id = activeId(db)
        require(summary.toByteArray(Charsets.UTF_8).size <= MAX_SUMMARY_BYTES)
        require(retained != expected)
        require(validProjection(expected, retained))
        if (activeMessages(db, id) != expected) throw IllegalStateException("Active context changed")
        if (!AgentSessionCodec.fits(retained, summary.toByteArray(Charsets.UTF_8).size)) {
            throw AgentSessionLimitException(AgentSessionCodec.MAX_SERIALIZED_BYTES + 1)
        }
        val through = db.rawQuery("SELECT MAX(id) FROM events WHERE session_id=?", arrayOf(id)).use {
            it.moveToFirst(); if (it.isNull(0)) 0L else it.getLong(0)
        }
        val prefixHash = canonicalPrefixHash(db, id, through)
        val activeHash = hashMessages(retained)
        val summaryHash = hash(summary)
        db.beginTransaction()
        try {
            db.insertOrThrow("compactions", null, ContentValues().apply {
                put("session_id", id); put("through_event_id", through)
                put("summary", summary); put("before_count", expected.size)
                put("after_count", retained.size); put("created_at", System.currentTimeMillis())
                put("prefix_hash", prefixHash); put("active_hash", activeHash)
                put("summary_hash", summaryHash)
            })
            db.delete("active_events", "session_id=?", arrayOf(id))
            retained.forEach { message ->
                db.insertOrThrow("active_events", null, ContentValues().apply {
                    put("session_id", id)
                    put("payload", json.encodeToString(AgentMessage.serializer(), message))
                })
            }
            db.execSQL("UPDATE sessions SET summary=?, active_generation=active_generation+1 WHERE id=?", arrayOf(summary, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        summaryBytes = summary.toByteArray(Charsets.UTF_8).size
    }

    suspend fun fullEventCount(id: String): Int = onDatabase { db ->
        db.rawQuery("SELECT COUNT(*) FROM events WHERE session_id=?", arrayOf(id)).use {
            it.moveToFirst(); it.getInt(0)
        }
    }

    suspend fun recordUsage(sessionId: String, turnId: String, providerId: String, model: String,
                            usage: AgentUsage?, requestMessages: List<AgentMessage> = emptyList(),
                            system: String = "") = onDatabase { db ->
        val generation = db.rawQuery("SELECT active_generation FROM sessions WHERE id=?", arrayOf(sessionId)).use {
            if (!it.moveToFirst()) throw IllegalArgumentException("Unknown session")
            it.getInt(0)
        }
        db.insertOrThrow("usage", null, ContentValues().apply {
            put("session_id", sessionId); put("turn_id", turnId)
            put("provider_id", providerId); put("model", model)
            put("source", if (usage == null) "unknown" else "reported")
            put("input_tokens", usage?.inputTokens); put("output_tokens", usage?.outputTokens)
            put("cache_read_tokens", usage?.cacheReadTokens)
            put("reasoning_tokens", usage?.reasoningTokens)
            put("total_tokens", usage?.totalTokens)
            put("base_count", requestMessages.size)
            put("base_last_hash", requestMessages.lastOrNull()?.let(::messageHash))
            put("system_hash", hash(system))
            put("active_generation", generation)
            put("created_at", System.currentTimeMillis())
        })
    }

    suspend fun contextEstimate(sessionId: String, providerId: String, model: String,
                                messages: List<AgentMessage>, system: String): ContextEstimate = onDatabase { db ->
        val generation = db.rawQuery("SELECT active_generation FROM sessions WHERE id=?", arrayOf(sessionId)).use {
            if (!it.moveToFirst()) throw IllegalArgumentException("Unknown session")
            it.getInt(0)
        }
        val anchor = db.rawQuery("SELECT input_tokens,output_tokens,base_count,base_last_hash,system_hash,active_generation " +
            "FROM usage WHERE session_id=? AND provider_id=? AND model=? AND source='reported' AND input_tokens IS NOT NULL " +
            "ORDER BY id DESC LIMIT 1", arrayOf(sessionId, providerId, model)).use { cursor ->
            if (!cursor.moveToFirst()) null else listOf(
                cursor.getLong(0).toString(), if (cursor.isNull(1)) "0" else cursor.getLong(1).toString(),
                cursor.getInt(2).toString(), if (cursor.isNull(3)) "" else cursor.getString(3),
                cursor.getString(4), cursor.getInt(5).toString())
        }
        if (anchor != null) {
            val count = anchor[2].toInt()
            if (anchor[5].toInt() == generation && anchor[4] == hash(system) &&
                count in 1..messages.size && anchor[3] == messageHash(messages[count - 1])) {
                val later = messages.drop(count).let { tail ->
                    if (tail.firstOrNull()?.role == AgentRole.Assistant) tail.drop(1) else tail
                }
                return@onDatabase ContextEstimate(anchor[0].toLong() + anchor[1].toLong() +
                    (AgentContext.serializedBytes(later) / 4),
                    if (later.isEmpty()) "reported" else "estimated")
            }
        }
        val approximate = (AgentContext.serializedBytes(messages).toLong() +
            system.toByteArray(Charsets.UTF_8).size) / 4
        ContextEstimate(approximate, "estimated")
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
                    val payload = json.encodeToString(AgentMessage.serializer(), message)
                    db.insertOrThrow("events", null, ContentValues().apply {
                        put("session_id", id)
                        put("payload", payload)
                    })
                    db.insertOrThrow("active_events", null, ContentValues().apply {
                        put("session_id", id); put("payload", payload)
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

    private fun activeMessages(db: SQLiteDatabase, id: String): List<AgentMessage> =
        db.rawQuery("SELECT payload FROM active_events WHERE session_id=? ORDER BY id", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }
        }

    private fun recoverInterrupted(db: SQLiteDatabase, id: String, interrupted: Boolean,
                                   active: List<AgentMessage>, summaryBytes: Int): List<AgentMessage> {
        val pending = active.lastOrNull()?.takeIf {
            interrupted && it.role == AgentRole.Assistant && it.toolCalls.isNotEmpty()
        } ?: return active
        val result = AgentMessage.tools(pending.toolCalls.map { call ->
            AgentToolResult(call.id, call.name, AgentToolError.content("interrupted"), true,
                "Interrupted  ${call.name}".take(256))
        })
        val fits = AgentSessionCodec.fits(active + result, summaryBytes)
        val payload = json.encodeToString(AgentMessage.serializer(), result)
        db.beginTransaction()
        try {
            db.insertOrThrow("events", null, ContentValues().apply {
                put("session_id", id); put("payload", payload)
            })
            if (fits) db.insertOrThrow("active_events", null, ContentValues().apply {
                put("session_id", id); put("payload", payload)
            }) else db.execSQL("DELETE FROM active_events WHERE id=(SELECT MAX(id) FROM active_events WHERE session_id=?)",
                arrayOf(id))
            db.execSQL("UPDATE sessions SET active_generation=active_generation+1, updated_at=? WHERE id=?",
                arrayOf(System.currentTimeMillis(), id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return if (fits) active + result else active.dropLast(1)
    }

    private fun summary(db: SQLiteDatabase, id: String): String =
        db.rawQuery("SELECT summary FROM sessions WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) throw IllegalArgumentException("Unknown session")
            it.getString(0)
        }

    private fun decode(payload: String): AgentMessage =
        json.decodeFromString(AgentMessage.serializer(), payload)

    private fun messageHash(message: AgentMessage): String = hash(json.encodeToString(AgentMessage.serializer(), message))

    private fun hashMessages(messages: List<AgentMessage>): String =
        hash(json.encodeToString(ListSerializer(AgentMessage.serializer()), messages))

    private fun canonicalPrefixHash(db: SQLiteDatabase, id: String, through: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        db.rawQuery("SELECT id,payload FROM events WHERE session_id=? AND id<=? ORDER BY id",
            arrayOf(id, through.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = cursor.getString(1).toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(12).putLong(cursor.getLong(0)).putInt(payload.size).array())
                digest.update(payload)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private data class Boundary(
        val rowId: Long,
        val through: Long,
        val afterCount: Int,
        val summary: String,
        val prefixHash: String?,
        val activeHash: String?,
        val summaryHash: String?,
    )

    private fun validateProjection(db: SQLiteDatabase, id: String) {
        val boundary = db.rawQuery("SELECT id,through_event_id,after_count,summary,prefix_hash," +
            "active_hash,summary_hash FROM compactions WHERE session_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) null else Boundary(cursor.getLong(0), cursor.getLong(1),
                cursor.getInt(2), cursor.getString(3),
                if (cursor.isNull(4)) null else cursor.getString(4),
                if (cursor.isNull(5)) null else cursor.getString(5),
                if (cursor.isNull(6)) null else cursor.getString(6))
        } ?: return
        val active = activeMessages(db, id)
        val currentSummary = summary(db, id)
        val shapeValid = boundary.afterCount in 0..active.size &&
            canonicalSuffixMatches(db, id, boundary.through, active.drop(boundary.afterCount)) &&
            currentSummary == boundary.summary
        val prefix = if (shapeValid) canonicalPrefixHash(db, id, boundary.through) else ""
        val valid = shapeValid && if (boundary.prefixHash != null && boundary.activeHash != null &&
            boundary.summaryHash != null) {
            prefix == boundary.prefixHash &&
                hashMessages(active.take(boundary.afterCount)) == boundary.activeHash &&
                hash(currentSummary) == boundary.summaryHash
        } else canonicalSubsequence(db, id, boundary.through, active.take(boundary.afterCount))
        if (valid) {
            if (boundary.prefixHash == null) db.execSQL("UPDATE compactions SET prefix_hash=?,active_hash=?,summary_hash=? WHERE id=?",
                arrayOf(prefix, hashMessages(active.take(boundary.afterCount)), hash(currentSummary), boundary.rowId))
            return
        }
        rebuildProjection(db, id, active.size)
    }

    private fun canonicalSubsequence(db: SQLiteDatabase, id: String, through: Long,
                                     retained: List<AgentMessage>): Boolean {
        var index = 0
        db.rawQuery("SELECT payload FROM events WHERE session_id=? AND id<=? ORDER BY id",
            arrayOf(id, through.toString())).use { cursor ->
            while (cursor.moveToNext() && index < retained.size) {
                if (decode(cursor.getString(0)) == retained[index]) index++
            }
        }
        return index == retained.size
    }

    private fun canonicalSuffixMatches(db: SQLiteDatabase, id: String, through: Long,
                                       activeSuffix: List<AgentMessage>): Boolean {
        var index = 0
        db.rawQuery("SELECT payload FROM events WHERE session_id=? AND id>? ORDER BY id",
            arrayOf(id, through.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                if (index >= activeSuffix.size || decode(cursor.getString(0)) != activeSuffix[index]) return false
                index++
            }
        }
        return index == activeSuffix.size
    }

    private fun rebuildProjection(db: SQLiteDatabase, id: String, oldCount: Int) {
        val recent = db.rawQuery("SELECT payload FROM events WHERE session_id=? ORDER BY id DESC LIMIT 256",
            arrayOf(id)).use { cursor ->
            buildList {
                var bytes = 0
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(0)
                    val size = payload.toByteArray(Charsets.UTF_8).size
                    if (isNotEmpty() && bytes + size > 1024 * 1024) break
                    add(decode(payload))
                    bytes += size
                }
            }.asReversed()
        }
        val pending = recent.lastOrNull()?.takeIf { it.role == AgentRole.Assistant && it.toolCalls.isNotEmpty() }
        val tail = recent.dropLast(if (pending == null) 0 else 1).toMutableList()
        fun dropFirstGroup() {
            if (tail.isEmpty()) return
            val paired = tail.first().role == AgentRole.Assistant && tail.first().toolCalls.isNotEmpty() &&
                tail.getOrNull(1)?.role == AgentRole.Tool
            tail.removeAt(0)
            if (paired) tail.removeAt(0)
            while (tail.firstOrNull()?.role == AgentRole.Tool) tail.removeAt(0)
        }
        while (tail.firstOrNull()?.role == AgentRole.Tool) tail.removeAt(0)
        while (tail.isNotEmpty() && (!AgentSessionCodec.fits(tail + listOfNotNull(pending)) ||
                !AgentContext.validGroups(tail))) dropFirstGroup()
        val recovered = tail + listOfNotNull(pending)
        val through = db.rawQuery("SELECT MAX(id) FROM events WHERE session_id=?", arrayOf(id)).use {
            it.moveToFirst(); if (it.isNull(0)) 0L else it.getLong(0)
        }
        val prefix = canonicalPrefixHash(db, id, through)
        db.beginTransaction()
        try {
            db.delete("active_events", "session_id=?", arrayOf(id))
            recovered.forEach { message ->
                db.insertOrThrow("active_events", null, ContentValues().apply {
                    put("session_id", id); put("payload", json.encodeToString(AgentMessage.serializer(), message))
                })
            }
            db.execSQL("UPDATE sessions SET summary='',active_generation=active_generation+1 WHERE id=?", arrayOf(id))
            db.insertOrThrow("compactions", null, ContentValues().apply {
                put("session_id", id); put("through_event_id", through); put("summary", "")
                put("before_count", oldCount); put("after_count", recovered.size)
                put("created_at", System.currentTimeMillis())
                put("prefix_hash", prefix); put("active_hash", hashMessages(recovered))
                put("summary_hash", hash(""))
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        summaryBytes = 0
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun validProjection(expected: List<AgentMessage>, retained: List<AgentMessage>): Boolean {
        var original = 0
        for (message in retained) {
            while (original < expected.size && !sameOrPruned(expected[original], message)) original++
            if (original == expected.size) return false
            original++
        }
        for (index in retained.indices) {
            val message = retained[index]
            if (message.role == AgentRole.Assistant && message.toolCalls.isNotEmpty()) {
                val results = retained.getOrNull(index + 1)?.takeIf { it.role == AgentRole.Tool }?.toolResults
                    ?: return false
                if (results.map { it.callId } != message.toolCalls.map { it.id }) return false
            }
            if (message.role == AgentRole.Tool) {
                val calls = retained.getOrNull(index - 1)?.takeIf { it.role == AgentRole.Assistant }?.toolCalls
                    ?: return false
                if (calls.map { it.id } != message.toolResults.map { it.callId }) return false
            }
        }
        return true
    }

    private fun sameOrPruned(original: AgentMessage, projected: AgentMessage): Boolean {
        if (original == projected) return true
        if (original.role != AgentRole.Tool || projected.role != AgentRole.Tool ||
            original.toolResults.size != projected.toolResults.size ||
            original.copy(toolResults = projected.toolResults) != projected) return false
        return original.toolResults.zip(projected.toolResults).all { (before, after) ->
            if (before == after) true else {
                val marker = try { json.parseToJsonElement(after.content).jsonObject["output_pruned"]
                    ?.jsonPrimitive?.booleanOrNull == true }
                    catch (_: IllegalArgumentException) { false }
                !before.error && marker && after.content.toByteArray(Charsets.UTF_8).size <
                    before.content.toByteArray(Charsets.UTF_8).size &&
                    before.copy(content = after.content) == after
            }
        }
    }

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

    companion object { const val MAX_SUMMARY_BYTES = 16 * 1024 }

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

private class SessionDatabase(context: Context) : SQLiteOpenHelper(context, "coding-sessions.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, workspace_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, interrupted INTEGER NOT NULL DEFAULT 0, summary TEXT NOT NULL DEFAULT '', active_generation INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX events_session_id ON events(session_id,id)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createUsage(db)
        createCompaction(db)
        addProjectionHashes(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1 || newVersion != 5) throw IllegalStateException("Unsupported session database upgrade $oldVersion to $newVersion")
        if (oldVersion == 1) createUsage(db)
        if (oldVersion <= 2) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
            createCompaction(db)
            db.execSQL("INSERT INTO active_events(session_id,payload) SELECT session_id,payload FROM events ORDER BY id")
        }
        if (oldVersion <= 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN active_generation INTEGER NOT NULL DEFAULT 0")
            if (oldVersion >= 2) {
                db.execSQL("ALTER TABLE usage ADD COLUMN base_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE usage ADD COLUMN base_last_hash TEXT")
                db.execSQL("ALTER TABLE usage ADD COLUMN system_hash TEXT")
                db.execSQL("ALTER TABLE usage ADD COLUMN active_generation INTEGER NOT NULL DEFAULT 0")
            }
        }
        if (oldVersion <= 4) addProjectionHashes(db)
    }

    private fun createUsage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usage (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, turn_id TEXT NOT NULL, provider_id TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER, cache_read_tokens INTEGER, reasoning_tokens INTEGER, total_tokens INTEGER, created_at INTEGER NOT NULL, base_count INTEGER NOT NULL DEFAULT 0, base_last_hash TEXT, system_hash TEXT, active_generation INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX usage_session_id ON usage(session_id,id)")
    }

    private fun createCompaction(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE active_events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX active_events_session_id ON active_events(session_id,id)")
        db.execSQL("CREATE TABLE compactions (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, through_event_id INTEGER NOT NULL, summary TEXT NOT NULL, before_count INTEGER NOT NULL, after_count INTEGER NOT NULL, created_at INTEGER NOT NULL)")
    }

    private fun addProjectionHashes(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE compactions ADD COLUMN prefix_hash TEXT")
        db.execSQL("ALTER TABLE compactions ADD COLUMN active_hash TEXT")
        db.execSQL("ALTER TABLE compactions ADD COLUMN summary_hash TEXT")
    }
}
