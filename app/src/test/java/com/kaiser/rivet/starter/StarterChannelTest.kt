package com.kaiser.rivet.starter

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StarterChannelTest {
    @get:Rule val temp = TemporaryFolder()
    private val bundled = listOf(
        Starter("world", "Bundled world", "Explore", "world.zip", 12, "Ask first"),
        Starter("city", "Bundled city", "Drive", "city.zip", 12, "Ask first"),
    )
    private val zip = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { archive ->
            for (path in listOf("README.md", "index.html")) {
                archive.putNextEntry(ZipEntry(path))
                archive.write("starter".encodeToByteArray())
                archive.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun validChannelOverridesInPlaceExtendsAndSkipsNewerAppVersions() {
        val reads = mutableListOf<String>()
        val gallery = channel(index(row("world"), row("other"), row("future", version = 12))) { url ->
            reads += url
            zip
        }.resolve(bundled)
        assertEquals(listOf("world", "city", "other"), gallery.map { it.starter.id })
        assertEquals(listOf("Remote world", "Bundled city", "Remote other"), gallery.map { it.starter.name })
        assertTrue(gallery.first().cachedZip!!.readBytes().contentEquals(zip))
        assertEquals(listOf("https://example.org/world.zip"), reads)
    }

    @Test fun checksumMismatchRefusesEntireChannelAndLeavesNoArchive() {
        val gallery = channel(index(row("world", sha = "0".repeat(64)))) { zip }.resolve(bundled)
        assertEquals(bundled, gallery.map { it.starter })
        assertFalse(temp.root.listFiles().orEmpty().any { it.extension == "zip" })
    }

    @Test fun offlineOrInvalidIndexUsesOnlyBundledEntries() {
        val offline = StarterChannel(temp.root, 11) { _, _ -> throw IOException("offline") }
        assertEquals(bundled, offline.resolve(bundled).map { it.starter })
        for (document in listOf(index(row("world"), schema = 2), index(row("world"), row("world")),
            index(row("world").replace("https://", "http://")), index(row("world", sha = "bad")),
            index(row("world", version = 0)))) {
            val result = channel(document) { error("Invalid index must not fetch a zip") }.resolve(bundled)
            assertEquals(bundled, result.map { it.starter })
        }
    }

    @Test fun invalidContentAndCorruptCachedFilesNeverReachTheGallery() {
        val wrongBytes = "not an archive".encodeToByteArray()
        val wrong = channel(index(row("world", sha = starterSha256(wrongBytes), bytes = wrongBytes.size))) { wrongBytes }
        assertEquals(bundled, wrong.resolve(bundled).map { it.starter })
        val working = channel(index(row("world"))) { zip }
        val file = working.resolve(bundled).first().cachedZip!!
        file.writeBytes(zip.copyOf().apply { this[0] = 0 })
        val retried = channel(index(row("world"))) { throw IOException("offline zip") }.resolve(bundled)
        assertEquals(bundled, retried.map { it.starter })
    }

    private fun channel(json: String, archive: (String) -> ByteArray) =
        StarterChannel(temp.root, 11) { url, _ ->
            if (url == StarterChannel.REMOTE_URL) json.encodeToByteArray() else archive(url)
        }

    private fun index(vararg rows: String, schema: Int = 1) =
        """{"schema":$schema,"starters":[${rows.joinToString()}]}"""

    private fun row(id: String, sha: String = starterSha256(zip), version: Int = 4, bytes: Int = zip.size) =
        """{"id":"$id","name":"Remote $id","blurb":"Try it","zip":"$id.zip","bytes":$bytes,"brief":"Ask first","zipUrl":"https://example.org/$id.zip","sha256":"$sha","minAppVersion":$version}"""
}
