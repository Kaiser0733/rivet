package com.kaiser.rivet.updates

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.android.apksig.ApkVerifier
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ApkIdentity(val packageName: String, val versionName: String, val versionCode: Long,
                                val signers: Set<String>)

internal class UpdateApks(
    private val context: Context,
    private val installed: () -> ApkIdentity? = {
        identity(context.packageManager.getPackageInfo(context.packageName, signatureFlags()))
    },
    private val archive: (File) -> ApkIdentity? = {
        context.packageManager.getPackageArchiveInfo(it.path, signatureFlags())?.let(::identity)
    },
    private val signatureValid: (File) -> Boolean = { file ->
        ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify().isVerified
    },
) {
    internal class Verified internal constructor(val file: File, val release: ReleaseUpdate) : Closeable {
        override fun close() { file.delete() }
    }

    suspend fun verify(file: File, release: ReleaseUpdate): Verified = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile || file.length() != release.size || file.length() !in 1..GitHubUpdates.MAX_APK_BYTES ||
                !signatureValid(file)) throw verificationFailure()
            val current = installed() ?: throw verificationFailure()
            val candidate = archive(file) ?: throw verificationFailure()
            if (current.packageName != PACKAGE || candidate.packageName != PACKAGE ||
                candidate.versionName != release.version.toString() ||
                candidate.versionCode <= current.versionCode || current.signers.isEmpty() ||
                candidate.signers != current.signers) throw verificationFailure()
            Verified(file, release)
        } catch (e: CancellationException) { file.delete(); throw e
        } catch (_: Exception) { file.delete(); throw verificationFailure() }
    }

    @RequiresApi(29)
    suspend fun saveToDownloads(verified: Verified): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(verified.release.filename, "${Environment.DIRECTORY_DOWNLOADS}/"), null)?.use {
            if (it.moveToFirst()) throw UpdateFailure("This update already has a file in Downloads. Remove that file before downloading it again.")
        } ?: throw UpdateFailure("Rivet couldn't check the Downloads folder before saving.")
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, verified.release.filename)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: throw UpdateFailure("Rivet couldn't save the update to Downloads.")
        try {
            copy(verified, uri)
            val confirmed = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                it.moveToFirst() && it.getString(0) == verified.release.filename
            } == true
            if (!confirmed) throw UpdateFailure("A file with this name already exists in Downloads. Remove it before trying again.")
            currentCoroutineContext().ensureActive()
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1) {
                throw UpdateFailure("Rivet couldn't finish saving the update to Downloads.")
            }
            uri
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    suspend fun saveDocument(verified: Verified, uri: Uri) = withContext(Dispatchers.IO) {
        try { copy(verified, uri) }
        catch (e: Exception) {
            // This is the document the user just created for this export, never
            // an arbitrary project document or an existing Downloads entry.
            try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            catch (_: Exception) {}
            throw e
        }
    }

    private suspend fun copy(verified: Verified, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: throw UpdateFailure("Rivet couldn't write the update to that location.")
        var total = 0L
        output.use { destination ->
            verified.file.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > verified.release.size) throw verificationFailure()
                    destination.write(buffer, 0, count)
                }
            }
        }
        if (total != verified.release.size) throw verificationFailure()
    }

    suspend fun discardPendingExports() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Downloads.IS_PENDING}=1 " +
                "AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf(context.packageName, "Rivet-v%.apk"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
            }
        }
    }

    companion object {
        const val PACKAGE = "com.kaiser.rivet"
        const val APK_MIME = "application/vnd.android.package-archive"
        fun saveIntent(verified: Verified) = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(APK_MIME)
            .putExtra(Intent.EXTRA_TITLE, verified.release.filename)

        @Suppress("DEPRECATION")
        private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        private fun identity(info: PackageInfo): ApkIdentity? {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            if (signatures.isNullOrEmpty()) return null
            val digests = signatures.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            }.toSet()
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            return ApkIdentity(info.packageName, info.versionName ?: return null, code, digests)
        }
        private fun verificationFailure() = UpdateFailure(
            "This APK didn't match Rivet's package, version, or signing certificate, or its signature was invalid. Nothing was saved.")
    }
}
