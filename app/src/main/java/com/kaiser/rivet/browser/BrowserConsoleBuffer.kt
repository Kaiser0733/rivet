package com.kaiser.rivet.browser

import java.util.Locale

enum class BrowserConsoleLevel {
    Log,
    Info,
    Warn,
    Error,
    Debug,
    NetworkError,
}

data class BrowserConsoleEntry(
    val id: Long,
    val timestampMs: Long,
    val level: BrowserConsoleLevel,
    val source: String,
    val line: Int,
    val message: String,
    val url: String,
)

data class BrowserConsoleQueryResult(
    val entries: List<BrowserConsoleEntry>,
    val totalBuffered: Int,
    val errorCount: Int,
    val nextSinceId: Long,
    val truncated: Boolean,
)

/**
 * Bounded console history for browser diagnostics. The buffer is deliberately
 * independent of WebView so console capture and querying can be tested without a
 * renderer; BrowserSession is the only producer in production.
 */
class BrowserConsoleBuffer(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxMessageChars: Int = MAX_MESSAGE_CHARS,
    private val maxSourceChars: Int = MAX_SOURCE_CHARS,
    private val maxResultBytes: Int = MAX_RESULT_BYTES,
) {
    private val entries = ArrayDeque<BrowserConsoleEntry>(maxEntries)
    private var nextId = 1L

    @Synchronized
    fun add(
        level: BrowserConsoleLevel,
        source: String,
        line: Int,
        message: String,
        url: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): BrowserConsoleEntry {
        val entry = BrowserConsoleEntry(
            id = nextId++,
            timestampMs = timestampMs,
            level = level,
            source = source.take(maxSourceChars),
            line = line.coerceAtLeast(0),
            message = message.take(maxMessageChars),
            url = url.take(MAX_URL_CHARS),
        )
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
        return entry
    }

    @Synchronized
    fun query(
        level: String = "all",
        sinceId: Long = 0L,
        limit: Int = 50,
        clear: Boolean = false,
    ): BrowserConsoleQueryResult {
        val total = entries.size
        val errors = errorCountLocked()
        val allowed = levelsFor(level)
        val boundedLimit = limit.coerceIn(1, MAX_LIMIT)
        val matching = entries.asSequence()
            .filter { it.id > sinceId && it.level in allowed }
            .toList()
        val limited = matching.take(boundedLimit)
        val selected = ArrayDeque<BrowserConsoleEntry>()
        var bytes = 0
        // Keep the newest entries when the byte budget is tight; the oldest slice is
        // the useful thing to drop because since_id lets the caller re-read from a
        // cursor rather than from the beginning.
        for (entry in limited.asReversed()) {
            val cost = entryCost(entry)
            if (selected.isNotEmpty() && bytes + cost > maxResultBytes) break
            selected.addFirst(entry)
            bytes += cost
        }
        val truncated = selected.size < limited.size
        val next = selected.lastOrNull()?.id ?: sinceId
        if (clear) entries.clear()
        return BrowserConsoleQueryResult(
            entries = selected.toList(),
            totalBuffered = total,
            errorCount = errors,
            nextSinceId = next,
            truncated = truncated,
        )
    }

    @Synchronized
    fun errorCount(): Int = errorCountLocked()

    @Synchronized
    fun lastErrorMessage(): String? =
        entries.lastOrNull { it.level == BrowserConsoleLevel.Error ||
            it.level == BrowserConsoleLevel.NetworkError }?.message

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun errorCountLocked(): Int = entries.count {
        it.level == BrowserConsoleLevel.Error || it.level == BrowserConsoleLevel.NetworkError
    }

    private fun levelsFor(level: String): Set<BrowserConsoleLevel> = when (level.lowercase(Locale.ROOT)) {
        "error" -> setOf(BrowserConsoleLevel.Error, BrowserConsoleLevel.NetworkError)
        "warn" -> setOf(BrowserConsoleLevel.Warn, BrowserConsoleLevel.Error,
            BrowserConsoleLevel.NetworkError)
        "log" -> setOf(BrowserConsoleLevel.Log, BrowserConsoleLevel.Info,
            BrowserConsoleLevel.Debug)
        else -> BrowserConsoleLevel.entries.toSet()
    }

    private fun entryCost(entry: BrowserConsoleEntry): Int =
        entry.message.toByteArray(Charsets.UTF_8).size +
            entry.source.toByteArray(Charsets.UTF_8).size +
            entry.url.toByteArray(Charsets.UTF_8).size + 96

    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_MESSAGE_CHARS = 512
        const val MAX_SOURCE_CHARS = 160
        const val MAX_RESULT_BYTES = 8 * 1_024
        const val MAX_LIMIT = 100
        private const val MAX_URL_CHARS = 2_048
    }
}
