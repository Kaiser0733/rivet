package com.kaiser.rivet.updates

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import com.android.apksig.ApkSigner
import okhttp3.tls.HeldCertificate
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
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

    @Test fun identityVersionAndSignerFailuresDeletePrivateApk() = runBlocking<Unit> {
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

    @Test fun validUpdateIsAcceptedOnlyAfterSignatureVerification() = runBlocking<Unit> {
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

    @Test fun legacySavePickerUsesVerifiedReleaseFilenameAndApkMime() = runBlocking<Unit> {
        val verified = UpdateApks(app, { installed }, { valid }, { true }).verify(fixture(), release)
        val intent = UpdateApks.saveIntent(verified)
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("Rivet-v0.11.1.apk", intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.categories.contains(android.content.Intent.CATEGORY_OPENABLE))
        verified.close()
    }

    @Suppress("DEPRECATION")
    @Test fun archiveIdentityUsesCurrentModernSignersOrLegacySignatures() = runBlocking<Unit> {
        val signature = Signature(byteArrayOf(1, 2, 3))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val packageInfo = PackageInfo().apply {
            packageName = "com.kaiser.rivet"
            versionName = "0.11.1"
            versionCode = 23
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                    shadowOf(it).setSignatures(arrayOf(signature))
                    shadowOf(it).setPastSigningCertificates(arrayOf(Signature(byteArrayOf(9))))
                }
            } else signatures = arrayOf(signature)
        }
        val file = fixture()
        shadowOf(app.packageManager).setPackageArchiveInfo(file.path, packageInfo)
        val verified = UpdateApks(app, { installed.copy(signers = setOf(digest)) }, signatureValid = { true })
            .verify(file, release)
        verified.close()
    }

    @Test fun actualSignedApkVerifiesAndTamperingCannotReuseItsCertificate() = runBlocking<Unit> {
        val unsigned = File.createTempFile("unsigned-update", ".zip", app.cacheDir)
        val payload = "original fixture content".toByteArray()
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(javaClass.getResourceAsStream("/update-manifest.bin")!!.use { it.readBytes() })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("fixture.txt").apply {
                method = ZipEntry.STORED; size = payload.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(payload) }.value
            })
            zip.write(payload); zip.closeEntry()
        }
        val key = HeldCertificate.Builder().build()
        val signed = File.createTempFile("signed-update", ".apk", app.cacheDir)
        val signer = ApkSigner.SignerConfig.Builder("test", key.keyPair.private, listOf(key.certificate)).build()
        ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(signed)
            .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true)
            .setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign()
        val downloaded = release.copy(size = signed.length())
        val apks = UpdateApks(app, { installed }, { valid })
        apks.verify(signed, downloaded)
        val bytes = signed.readBytes()
        val index = bytes.indices.first { i -> i + payload.size <= bytes.size &&
            bytes.copyOfRange(i, i + payload.size).contentEquals(payload) }
        bytes[index] = 'X'.code.toByte()
        signed.writeBytes(bytes)
        try { apks.verify(signed, downloaded); fail("Expected tampered signature rejection") }
        catch (_: UpdateFailure) {}
        assertFalse(signed.exists())
        unsigned.delete()
    }

    @Test fun unsignedCorruptArchiveCannotPassProductionVerifier() = runBlocking<Unit> {
        val file = fixture()
        try { UpdateApks(app).verify(file, release); fail("Expected corrupt archive rejection") }
        catch (_: UpdateFailure) {}
        assertFalse(file.exists())
    }
}
