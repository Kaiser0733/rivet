package com.kaiser.rivet.runtime

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DownloadFailure(val code: String, val statusCode: Int? = null) : Exception(code)

class StagedDownload internal constructor(
    val file: File,
    val size: Long,
    val sha256: String,
    private val onClose: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { file.delete() } finally { onClose() }
    }
}

/** A credential-free, bounded HTTPS client. Redirects are followed only after revalidation. */
class ProjectDownloader internal constructor(
    private val storage: File,
    // Internal transport injection keeps HTTPS behavior deterministic in unit tests.
    client: OkHttpClient? = null,
) {
    private val client = (client ?: newClient()).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .build()
    suspend fun download(url: String, expectedContentSha256: String? = null): StagedDownload {
        STAGING_MUTEX.lock()
        var unclaimed: StagedDownload? = null
        try {
            val staged = withTimeout(OVERALL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    if (expectedContentSha256 != null && !SHA256.matches(expectedContentSha256)) {
                        throw DownloadFailure("invalid_checksum")
                    }
                    var target = validatedUrl(url)
                    var redirects = 0
                    while (true) {
                        if (!storage.isDirectory && !storage.mkdirs()) throw DownloadFailure("storage")
                        // AgentLoop executes tools serially; remove a staged body left by a killed app process.
                        val staleFiles = storage.listFiles { file ->
                            file.name.startsWith(STAGING_PREFIX) && file.name.endsWith(STAGING_SUFFIX)
                        } ?: throw DownloadFailure("storage")
                        if (staleFiles.any { !it.delete() }) throw DownloadFailure("storage")
                        val staged = try { File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX, storage) }
                            catch (_: Exception) { throw DownloadFailure("storage") }
                        try {
                            when (val result = fetch(target, staged)) {
                                is FetchResult.Redirect -> {
                                    staged.delete()
                                    if (redirects >= MAX_REDIRECTS) throw DownloadFailure("redirect_blocked")
                                    val resolved = target.resolve(result.location)
                                        ?: throw DownloadFailure("redirect_blocked")
                                    target = try { validatedUrl(resolved.toString()) }
                                        catch (_: DownloadFailure) { throw DownloadFailure("redirect_blocked") }
                                    redirects++
                                }
                                is FetchResult.HttpError ->
                                    throw DownloadFailure("download_failed", result.statusCode)
                                is FetchResult.TooLarge -> throw DownloadFailure("download_too_large")
                                is FetchResult.Success -> {
                                    if (expectedContentSha256 != null &&
                                        !result.sha256.equals(expectedContentSha256, ignoreCase = true)) {
                                        throw DownloadFailure("download_sha_mismatch")
                                    }
                                    return@withContext StagedDownload(staged, result.size, result.sha256) {
                                        STAGING_MUTEX.unlock()
                                    }.also { unclaimed = it }
                                }
                            }
                        } catch (e: CancellationException) {
                            staged.delete()
                            throw e
                        } catch (e: DownloadFailure) {
                            staged.delete()
                            throw e
                        } catch (_: Exception) {
                            staged.delete()
                            throw DownloadFailure("network_error")
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") throw DownloadFailure("network_error")
                }
            }
            return staged
        } catch (e: CancellationException) {
            unclaimed?.close()
            throw e
        } catch (e: Exception) {
            unclaimed?.close()
            throw e
        } finally {
            if (unclaimed == null) STAGING_MUTEX.unlock()
        }
    }

    fun hostForDisplay(url: String): String = validatedUrl(url).host

    private suspend fun fetch(url: HttpUrl, target: File): FetchResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { value ->
                        if (value.code in REDIRECT_CODES) {
                            FetchResult.Redirect(value.header("Location") ?: return@use FetchResult.HttpError(value.code))
                        } else if (!value.isSuccessful) {
                            FetchResult.HttpError(value.code)
                        } else {
                            val body = value.body ?: return@use FetchResult.HttpError(value.code)
                            val contentLength = body.contentLength()
                            if (contentLength > MAX_DOWNLOAD_BYTES) return@use FetchResult.TooLarge
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            var tooLarge = false
                            FileOutputStream(target).use { output ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(BUFFER_BYTES)
                                    while (true) {
                                        if (!continuation.isActive) {
                                            call.cancel()
                                            throw CancellationException("Download cancelled")
                                        }
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        if (total > MAX_DOWNLOAD_BYTES) {
                                            tooLarge = true
                                            break
                                        }
                                        digest.update(buffer, 0, count)
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            if (tooLarge) FetchResult.TooLarge
                            else if (contentLength >= 0 && total != contentLength) FetchResult.HttpError(value.code)
                            else FetchResult.Success(total, digest.digest().toHex())
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: CancellationException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun validatedUrl(value: String): HttpUrl {
        if (value.length > MAX_URL_CHARS) throw DownloadFailure("invalid_url")
        val url = value.toHttpUrlOrNull() ?: throw DownloadFailure("invalid_url")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || url.host.isBlank()) throw DownloadFailure("invalid_url")
        return url
    }

    private sealed interface FetchResult {
        data class Redirect(val location: String) : FetchResult
        data class Success(val size: Long, val sha256: String) : FetchResult
        data class HttpError(val statusCode: Int) : FetchResult
        data object TooLarge : FetchResult
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        // Held until the staged bytes have been committed or discarded.
        private val STAGING_MUTEX = Mutex()
        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_URL_CHARS = 4096
        private const val BUFFER_BYTES = 64 * 1024
        private const val OVERALL_TIMEOUT_MS = 120_000L
        private const val STAGING_PREFIX = "rivet-download-"
        private const val STAGING_SUFFIX = ".tmp"
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        private fun newClient() = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
