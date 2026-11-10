package com.kaiser.rivet.browser

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSnapshotStoreTest {
    @Test fun savesAndLoadsSnapshotsOutsideTheSavedStateBundle() {
        val dir = Files.createTempDirectory("browser-snapshots").toFile()
        val store = BrowserSnapshotStore(dir)
        val snapshot = BrowserSnapshot("w1", "Docs", listOf("https://example.com"), 0,
            125, mapOf("q" to "kotlin"), fidelity = BrowserFidelity.Reloaded)
        assertTrue(store.save(snapshot))
        assertEquals(snapshot, store.load("w1"))
        assertEquals(1, store.loadAll().size)
        store.delete("w1")
        assertEquals(0, store.loadAll().size)
        dir.deleteRecursively()
    }

    @Test fun clearingRemovesJsonAndThumbnails() {
        val dir = Files.createTempDirectory("browser-snapshots").toFile()
        val store = BrowserSnapshotStore(dir)
        store.save(BrowserSnapshot("w2"))
        java.io.File(dir, "w2.webp").writeText("thumb")
        store.clear()
        assertEquals(0, store.bytes())
        dir.deleteRecursively()
    }
}
