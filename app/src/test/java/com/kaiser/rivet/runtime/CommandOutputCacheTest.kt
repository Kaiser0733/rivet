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

    @Test fun earlierSpillsRemainReadableAfterMoreThanEightCommandsAndReopening() {
        val cache = CommandOutputCache(dir)
        val first = cache.put((1..120).joinToString("\n") { "a$it" }, "err-a", workspace = "ws")!!
        repeat(9) { cache.put("b".repeat(600_000), "err", force = true) }
        val reopened = CommandOutputCache(dir)
        assertEquals("err-a", reopened.get(first)?.stderr)
        assertEquals("ws", reopened.get(first)?.workspace)
        assertEquals(10, reopened.size())
    }

    @Test fun bytePagingCanReadBeyondTheLinePreviewWithoutSplittingUtf8() {
        val cache = CommandOutputCache(dir)
        val prefix = "a".repeat(1_100_000)
        val id = cache.put("", prefix + "a".repeat(3071) + "😀tail", force = true)!!
        val entry = cache.get(id)!!
        assertTrue(entry.stderrTruncated)

        val first = entry.read(prefix.length.toLong(), stderr = true)
        assertEquals("a".repeat(3071), first.text)
        val tail = entry.read(first.end, stderr = true)
        assertEquals("😀tail", tail.text)
        assertEquals(tail.total, tail.end)
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
