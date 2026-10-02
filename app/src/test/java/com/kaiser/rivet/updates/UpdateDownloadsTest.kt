package com.kaiser.rivet.updates

import android.app.Application
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class UpdateDownloadsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: TestDownloadsProvider
    private lateinit var apks: UpdateApks
    private val release = ReleaseUpdate(ReleaseVersion(0, 11, 1), 4,
        "https://github.com/Kaiser0733/rivet/releases/download/v0.11.1/Rivet-v0.11.1.apk")

    @Before fun setup() {
        provider = Robolectric.buildContentProvider(TestDownloadsProvider::class.java).create(
            ProviderInfo().apply { authority = "media"; exported = true }).get()
        val installed = ApkIdentity("com.kaiser.rivet", "0.11.0", 22, setOf("pin"))
        apks = UpdateApks(app, { installed }, { installed.copy(versionName = "0.11.1", versionCode = 23) }, { true })
    }
    private suspend fun verified() = apks.verify(File.createTempFile("update-export", ".tmp", app.cacheDir)
        .apply { writeText("apk!") }, release)

    @Test fun verifiedExportHasExactFilenameAndBytesAndCannotDuplicate() = runBlocking {
        val verified = verified()
        val uri = apks.saveToDownloads(verified)
        val row = provider.rows.values.single()
        assertEquals("Rivet-v0.11.1.apk", row.values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals(0, row.values.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals("apk!", row.file.readText())
        try { apks.saveToDownloads(verified); fail("Expected existing-file refusal") } catch (_: UpdateFailure) {}
        assertEquals(1, provider.rows.size)
        assertEquals("apk!", row.file.readText())
        assertNotNull(uri)
        verified.close()
    }

    @Test fun failedOrNormalizedExportLeavesNoPublicPartialOrDuplicate() = runBlocking {
        val verified = verified()
        provider.failWrite = true
        try { apks.saveToDownloads(verified); fail("Expected write refusal") } catch (_: Exception) {}
        assertTrue(provider.rows.isEmpty())
        provider.failWrite = false
        provider.normalizeName = true
        try { apks.saveToDownloads(verified); fail("Expected normalized name refusal") } catch (_: UpdateFailure) {}
        assertTrue(provider.rows.isEmpty())
        provider.normalizeName = false
        provider.queriesUnavailable = true
        try { apks.saveToDownloads(verified); fail("Expected unavailable query refusal") } catch (_: UpdateFailure) {}
        assertTrue(provider.rows.isEmpty())
        verified.close()
    }

    @Test fun restartRemovesOnlyOwnedPendingExportsAndPreservesCompletedDownload() = runBlocking {
        val verified = verified()
        apks.saveToDownloads(verified)
        val completed = provider.rows.values.single()
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "Rivet-v0.11.2.apk")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val stale = provider.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
        provider.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
        provider.rows.values.last().values.put(MediaStore.Downloads.OWNER_PACKAGE_NAME, "another.app")
        apks.discardPendingExports()
        assertEquals(2, provider.rows.size)
        assertTrue(provider.rows.values.contains(completed))
        assertEquals("apk!", completed.file.readText())
        assertFalse(provider.rows.containsKey(android.content.ContentUris.parseId(stale)))
        verified.close()
    }
}
