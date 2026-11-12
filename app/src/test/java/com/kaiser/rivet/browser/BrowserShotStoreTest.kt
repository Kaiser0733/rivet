package com.kaiser.rivet.browser

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserShotStoreTest {
    @Test fun projectsGetDifferentShotDirectories() {
        val base = Files.createTempDirectory("shots").toFile()
        try {
            val a = BrowserShotStore(base, "tree://a")
            val b = BrowserShotStore(base, "tree://b")
            assertNotEquals(a.directory, b.directory)
            val shot = a.newShot(1L).apply { writeText("shot") }
            assertTrue(shot.isFile)
            assertEquals(1, a.cleanSession())
            assertTrue(!shot.exists())
        } finally { base.deleteRecursively() }
    }
}
