package com.kaiser.rivet.updates

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26, 28, 29])
class UpdateApksTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val release = ReleaseUpdate(ReleaseVersion(0, 11, 1), 4,
        "https://github.com/Kaiser0733/rivet/releases/download/v0.11.1/Rivet-v0.11.1.apk")
    private val installed = ApkIdentity("com.kaiser.rivet", "0.11.0", 22, setOf("pinned"))
    private val valid = installed.copy(versionName = "0.11.1", versionCode = 23)
    private fun fixture() = File.createTempFile("update-test", ".tmp", app.cacheDir).apply { writeText("apk!") }

    @Test fun identityVersionAndSignerFailuresDeletePrivateApk() = runBlocking {
        val invalid = listOf(null, valid.copy(packageName = "another.app"),
            valid.copy(versionName = "0.11.2"), valid.copy(versionCode = 22), valid.copy(versionCode = 21),
            valid.copy(signers = setOf("wrong")), valid.copy(signers = emptySet()))
        for (identity in invalid) {
            val file = fixture()
            val apks = UpdateApks(app, { installed }, { identity }, { true })
            try { apks.verify(file, release); fail("Expected APK rejection") } catch (_: UpdateFailure) {}
            assertFalse(file.exists())
        }
    }

    @Test fun validUpdateIsAcceptedOnlyAfterSignatureVerification() = runBlocking {
        val file = fixture()
        var inspected = false
        val apks = UpdateApks(app, { installed }, { valid }, { inspected = true; true })
        val verified = apks.verify(file, release)
        assertTrue(inspected)
        assertEquals("Rivet-v0.11.1.apk", verified.release.filename)
        assertTrue(file.exists())
        verified.close()
        assertFalse(file.exists())
        val corrupt = fixture()
        try { UpdateApks(app, { installed }, { valid }, { false }).verify(corrupt, release)
            fail("Expected invalid signature rejection") } catch (_: UpdateFailure) {}
        assertFalse(corrupt.exists())
    }

    @Test fun legacySavePickerUsesVerifiedReleaseFilenameAndApkMime() = runBlocking {
        val verified = UpdateApks(app, { installed }, { valid }, { true }).verify(fixture(), release)
        val intent = UpdateApks.saveIntent(verified)
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("Rivet-v0.11.1.apk", intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.categories.contains(android.content.Intent.CATEGORY_OPENABLE))
        verified.close()
    }

    @Test fun unsignedCorruptArchiveCannotPassProductionVerifier() = runBlocking {
        val file = fixture()
        try { UpdateApks(app).verify(file, release); fail("Expected corrupt archive rejection") }
        catch (_: UpdateFailure) {}
        assertFalse(file.exists())
    }
}
