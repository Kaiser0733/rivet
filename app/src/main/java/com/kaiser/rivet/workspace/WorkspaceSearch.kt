package com.kaiser.rivet.workspace

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SearchLimits(
    val maxFiles: Int = 1000,
    val maxEntries: Int = 5000,
    val maxBytes: Long = 8L * 1024 * 1024,
    val maxResults: Int = 200,
    val perFileBytes: Int = 256 * 1024,
) {
    init {
        require(maxFiles in 1..1000 && maxEntries in 1..5000 && maxBytes in 1..(8L * 1024 * 1024))
        require(maxResults in 1..200 && perFileBytes in 1..WorkspaceText.MAX_BYTES)
    }
}

data class SearchHit(val path: WorkspacePath, val line: Int?, val context: String, val directory: Boolean)
data class SearchReport(val hits: List<SearchHit>, val filesScanned: Int, val bytesScanned: Long,
    val entriesVisited: Int, val skipped: Int, val limited: Boolean)

suspend fun searchWorkspace(
    query: String,
    start: WorkspaceEntry,
    limits: SearchLimits,
    list: suspend (WorkspaceEntry, Int) -> List<WorkspaceEntry>,
    read: suspend (WorkspaceEntry, Int) -> ByteArray,
): SearchReport {
    require(query.isNotEmpty() && query.length <= 256)
    val hits = mutableListOf<SearchHit>()
    val pending = ArrayDeque<WorkspaceEntry>().apply { add(start) }
    val visited = mutableSetOf<String>().apply { if (start.directory) add(start.documentId) }
    var files = 0
    var bytes = 0L
    var entries = 0
    var skipped = 0
    var limited = false
    outer@ while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        if (entries >= limits.maxEntries) { limited = true; break }
        val next = pending.removeFirst()
        val children = try {
            if (next.directory) list(next, limits.maxEntries - entries).sortedWith(WorkspaceEntry.ORDER)
            else listOf(next)
        } catch (e: WorkspaceFailure) {
            // A failed provider listing can already have enumerated every allowed row.
            entries = limits.maxEntries
            limited = true
            skipped++
            break
        }
        for (entry in children) {
            currentCoroutineContext().ensureActive()
            if (entries >= limits.maxEntries || hits.size >= limits.maxResults) { limited = true; break@outer }
            entries++
            if (!visited.add(entry.documentId)) continue
            if (entry.path.value.contains(query)) hits.add(SearchHit(entry.path, null, entry.path.value.take(240), entry.directory))
            if (hits.size >= limits.maxResults) { limited = true; break@outer }
            if (entry.directory) {
                pending.add(entry)
                continue
            }
            if (files >= limits.maxFiles || bytes >= limits.maxBytes - 1) { limited = true; break@outer }
            files++
            val allowance = minOf(limits.perFileBytes.toLong(), limits.maxBytes - bytes - 1).toInt()
            if (entry.size != null && entry.size > allowance) { skipped++; limited = true; continue }
            val content = try {
                val raw = read(entry, allowance)
                bytes += raw.size
                if (raw.size > allowance) { limited = true; break@outer }
                WorkspaceText.decode(raw)
            } catch (e: WorkspaceFailure) {
                // Failed reads may have consumed their entire allowance. Charge conservatively.
                if (e.reason != WorkspaceFailure.Reason.BINARY) bytes += allowance + 1
                if (e.reason == WorkspaceFailure.Reason.TOO_LARGE) limited = true
                skipped++
                continue
            }
            content.lineSequence().forEachIndexed { index, line ->
                currentCoroutineContext().ensureActive()
                if (hits.size < limits.maxResults) {
                    val match = line.indexOf(query)
                    if (match >= 0) {
                        val offset = maxOf(0, match - 60)
                        hits.add(SearchHit(entry.path, index + 1, line.substring(offset).take(240), false))
                    }
                }
            }
            if (hits.size >= limits.maxResults) { limited = true; break@outer }
        }
    }
    return SearchReport(hits, files, bytes, entries, skipped, limited || entries >= limits.maxEntries)
}
