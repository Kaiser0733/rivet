package com.kaiser.rivet.starter

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterCatalogTest {
    @Test fun bundledAssetsMatchTheIndexAndContainRootProjects() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val directory = File(main, "assets/starters")
        val entries = StarterCatalog.parse(File(directory, "starters.json").readText())
        assertEquals(listOf("voxel-world", "open-city", "my-ai", "idea-lab"), entries.map { it.id })
        entries.forEach { entry ->
            val asset = File(directory, entry.zip)
            assertEquals(entry.bytes.toLong(), asset.length())
            assertTrue(asset.length() in 1L..StarterCatalog.MAX_ZIP_BYTES.toLong())
            ZipFile(asset).use { archive ->
                assertTrue(archive.entries().asSequence().all { !it.isDirectory && '/' !in it.name })
                for (name in listOf("README.md", "index.html")) {
                    assertTrue(archive.getInputStream(archive.getEntry(name)).use { it.read() } >= 0)
                }
            }
        }
        assertTrue(entries.sumOf { it.bytes } <= StarterCatalog.MAX_TOTAL_BYTES)
    }

    @Test fun rejectsUnknownMissingAndMalformedSchemas() {
        reject("""{"schema":2,"starters":[${entry()}]}""")
        reject("""{"starters":[${entry()}]}""")
        reject("""{"schema":"bad","starters":[]}""")
        reject("""{"schema":1,"starters":[]}""")
    }

    @Test fun rejectsDuplicateIdsAndUnsafePaths() {
        reject(document(entry(), entry()))
        for (id in listOf("", "../world", "a/b", "a\\b", ".hidden", "world%2f")) {
            reject(document(entry(id = id, zip = "safe.zip")))
        }
        for (zip in listOf("../world.zip", "/world.zip", "nested/world.zip", "other.zip")) {
            reject(document(entry(zip = zip)))
        }
    }

    @Test fun rejectsBlankCopyAndByteLimits() {
        reject(document(entry(name = " ")))
        reject(document(entry(blurb = "")))
        reject(document(entry(brief = " ")))
        reject(document(entry(bytes = 0)))
        reject(document(entry(bytes = -1)))
        reject(document(entry(bytes = StarterCatalog.MAX_ZIP_BYTES + 1)))
        reject(document(*(1..7).map { entry(id = "starter-$it", bytes = 150_000) }.toTypedArray()))
    }

    private fun reject(json: String) {
        assertThrows(StarterCatalogFailure::class.java) { StarterCatalog.parse(json) }
    }

    private fun document(vararg entries: String) = """{"schema":1,"starters":[${entries.joinToString()}]}"""

    private fun entry(id: String = "world", zip: String = "$id.zip", bytes: Int = 250,
                      name: String = "World", blurb: String = "Build", brief: String = "Ask first") =
        """{"id":"$id","name":"$name","blurb":"$blurb","zip":"$zip","bytes":$bytes,"brief":"$brief"}"""
}
