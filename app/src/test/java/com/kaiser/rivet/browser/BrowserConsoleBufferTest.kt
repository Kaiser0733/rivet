package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserConsoleBufferTest {
    private fun entry(buffer: BrowserConsoleBuffer, level: BrowserConsoleLevel,
                      message: String, id: Long = 0L) =
        buffer.add(level, "game.js", 42, message, "http://127.0.0.1:45179/", id)

    @Test fun ringEvictsOldestAndKeepsIdsMonotonic() {
        val buffer = BrowserConsoleBuffer(maxResultBytes = 100_000)
        repeat(205) { entry(buffer, BrowserConsoleLevel.Log, "line $it", it.toLong()) }
        val result = buffer.query(limit = 100)
        assertEquals(200, result.totalBuffered)
        assertEquals(6L, result.entries.first().id)
        assertEquals(105L, result.entries.last().id)
        assertTrue(result.entries.zipWithNext().all { (a, b) -> a.id < b.id })
        val tail = buffer.query(sinceId = 200)
        assertEquals(listOf(201L, 202L, 203L, 204L, 205L), tail.entries.map { it.id })
    }

    @Test fun levelFiltersGroupErrorsAndWarnings() {
        val buffer = BrowserConsoleBuffer()
        entry(buffer, BrowserConsoleLevel.Log, "log", 1)
        entry(buffer, BrowserConsoleLevel.Info, "info", 2)
        entry(buffer, BrowserConsoleLevel.Debug, "debug", 3)
        entry(buffer, BrowserConsoleLevel.Warn, "warn", 4)
        entry(buffer, BrowserConsoleLevel.Error, "error", 5)
        entry(buffer, BrowserConsoleLevel.NetworkError, "network", 6)
        assertEquals(6, buffer.query("all").entries.size)
        assertEquals(listOf("log", "info", "debug"),
            buffer.query("log").entries.map { it.message })
        assertEquals(listOf("warn", "error", "network"),
            buffer.query("warn").entries.map { it.message })
        assertEquals(listOf("error", "network"),
            buffer.query("error").entries.map { it.message })
        assertEquals(2, buffer.errorCount())
        assertEquals("network", buffer.lastErrorMessage())
    }

    @Test fun sinceIdLimitAndNextCursorAreIncremental() {
        val buffer = BrowserConsoleBuffer()
        repeat(10) { entry(buffer, BrowserConsoleLevel.Log, "line $it", it.toLong()) }
        val first = buffer.query(sinceId = 0, limit = 3)
        assertEquals(listOf(1L, 2L, 3L), first.entries.map { it.id })
        assertEquals(3L, first.nextSinceId)
        val second = buffer.query(sinceId = first.nextSinceId, limit = 3)
        assertEquals(listOf(4L, 5L, 6L), second.entries.map { it.id })
        assertEquals(6L, second.nextSinceId)
        assertEquals(1, buffer.query(limit = 0).entries.size)
        assertEquals(10, buffer.query(limit = 500).entries.size)
        val many = BrowserConsoleBuffer(maxResultBytes = 100_000)
        repeat(120) { entry(many, BrowserConsoleLevel.Log, "line $it", it.toLong()) }
        assertEquals(100, many.query(limit = 500).entries.size)
    }

    @Test fun clearReturnsTheSliceThenEmptiesTheBufferWithoutReusingIds() {
        val buffer = BrowserConsoleBuffer()
        entry(buffer, BrowserConsoleLevel.Error, "boom", 1)
        val result = buffer.query(clear = true)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.errorCount)
        assertEquals(0, buffer.query().entries.size)
        assertEquals(0, buffer.errorCount())
        val next = entry(buffer, BrowserConsoleLevel.Log, "after", 2)
        assertTrue(next.id > result.entries.last().id)
    }

    @Test fun messagesAndSourcesAreClamped() {
        val buffer = BrowserConsoleBuffer()
        val result = buffer.add(BrowserConsoleLevel.Error, "s".repeat(300), 7,
            "m".repeat(900), "http://127.0.0.1:45179/")
        assertEquals(512, result.message.length)
        assertEquals(160, result.source.length)
        assertNull(buffer.lastErrorMessage()?.takeIf { it.length > 512 })
    }

    @Test fun byteCapDropsOldestReturnedEntriesAndMarksTruncation() {
        val buffer = BrowserConsoleBuffer(maxResultBytes = 1_024)
        repeat(20) { entry(buffer, BrowserConsoleLevel.Log, "x".repeat(500), it.toLong()) }
        val result = buffer.query(limit = 100)
        assertTrue(result.truncated)
        assertTrue(result.entries.size < 20)
        val bytes = result.entries.sumOf {
            it.message.toByteArray(Charsets.UTF_8).size +
                it.source.toByteArray(Charsets.UTF_8).size +
                it.url.toByteArray(Charsets.UTF_8).size + 96
        }
        assertTrue(bytes <= 1_024)
        assertTrue(result.entries.last().id == 20L)
    }
}
