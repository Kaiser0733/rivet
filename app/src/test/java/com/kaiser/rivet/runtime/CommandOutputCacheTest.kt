package com.kaiser.rivet.runtime

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandOutputCacheTest {
    private val dir = Files.createTempDirectory("rivet-command-output").toFile()

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    @Test fun smallOutputsDoNotGetAPagingHandle() {
        val cache = CommandOutputCache(dir)
        assertNull(cache.put("one line", "err"))
        assertNull(cache.get("missing"))
    }

    @Test fun largeOutputsRoundTripAndEvictLeastRecentlyUsed() {
        val cache = CommandOutputCache(dir, maxEntries = 2, maxTotalBytes = 1_000_000)
        val first = cache.put((1..120).joinToString("\n") { "a$it" }, "err-a")!!
        val second = cache.put((1..120).joinToString("\n") { "b$it" }, "err-b")!!
        assertEquals("err-a", cache.get(first)!!.stderr)
        val third = cache.put((1..120).joinToString("\n") { "c$it" }, "err-c")!!
        assertNotNull(cache.get(first))
        assertNull(cache.get(second))
        assertNotNull(cache.get(third))
        assertEquals(2, cache.size())
    }

    @Test fun perStreamAndTotalBudgetsAreEnforced() {
        val cache = CommandOutputCache(dir, maxEntries = 8, maxStreamBytes = 100,
            maxTotalBytes = 250)
        val first = cache.put("a".repeat(120), "s".repeat(120), force = true)!!
        val entry = cache.get(first)!!
        assertTrue(entry.stdoutTruncated)
        assertTrue(entry.stderrTruncated)
        assertEquals(100, entry.stdout.length)
        assertEquals(100, entry.stderr.length)
        val second = cache.put("b".repeat(90), "", force = true)!!
        assertNull(cache.get(first))
        assertNotNull(cache.get(second))
    }

    @Test fun clearAndWorkspaceScopedClearRemoveOnlyTheirOwnFiles() {
        val cache = CommandOutputCache(dir)
        val a = cache.put((1..120).joinToString("\n"), "", workspace = "ws-a")!!
        val b = cache.put((1..120).joinToString("\n"), "", workspace = "ws-b")!!
        cache.clearForWorkspace("ws-a")
        assertNull(cache.get(a))
        assertNotNull(cache.get(b))
        cache.clear()
        assertNull(cache.get(b))
        assertEquals(0, cache.size())
        assertEquals(0, dir.listFiles().orEmpty().size)
    }
}
