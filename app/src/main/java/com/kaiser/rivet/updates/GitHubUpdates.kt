package com.kaiser.rivet.updates

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class UpdateFailure(message: String, val retryable: Boolean = false) : Exception(message)

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) = compareValuesBy(this, other,
        ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion? {
            if (!Regex("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})").matches(value)) return null
            val parts = value.split('.').map(String::toInt)
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class ReleaseUpdate(val version: ReleaseVersion, val size: Long, val url: String) {
    val filename get() = "Rivet-v$version.apk"
}

/** Separate from provider and project networking; no credentials or cookies are accepted. */
internal class GitHubUpdates(
    private val storage: File,
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).addInterceptor { chain ->
            try { chain.proceed(chain.request()) }
            catch (e: SecurityException) { throw IOException("Socket access denied", e) }
        }.build(),
    private val maxDownloadBytes: Long = MAX_APK_BYTES,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE).retryOnConnectionFailure(false).cache(null).build()

    init { require(maxDownloadBytes in 1..MAX_APK_BYTES) }

    suspend fun check(installedVersion: String): ReleaseUpdate? {
        val result = retrying {
            fetch(LATEST.toHttpUrlOrNull()!!, "application/vnd.github+json", CHECK_TRANSPORT) { response ->
            if (response.code == 404) return@fetch Fetch.NoRelease
            if (!response.isSuccessful) throw UpdateFailure("Rivet couldn't check GitHub for updates. Try again later.")
            val body = response.body ?: throw metadataFailure()
            if (body.contentLength() > MAX_METADATA_BYTES) throw metadataFailure()
            val buffer = Buffer()
            val source = body.source()
            while (true) {
                val read = source.read(buffer, (MAX_METADATA_BYTES + 1 - buffer.size).coerceAtLeast(1))
                if (read < 0) break
                if (buffer.size > MAX_METADATA_BYTES) throw metadataFailure()
            }
            Fetch.Body(buffer.readUtf8())
            }
        }
        return when (result) {
            Fetch.NoRelease -> null
            is Fetch.Body -> parseRelease(result.text, installedVersion)
            else -> throw metadataFailure()
        }
    }

    fun parseRelease(body: String, installedVersion: String): ReleaseUpdate? {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            if (root["draft"]?.jsonPrimitive?.booleanOrNull == true ||
                root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
            if (root["draft"]?.jsonPrimitive?.booleanOrNull != false ||
                root["prerelease"]?.jsonPrimitive?.booleanOrNull != false ||
                root["published_at"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) throw metadataFailure()
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw metadataFailure()
            val version = tag.takeIf { it.startsWith('v') }?.drop(1)?.let(ReleaseVersion::parse)
                ?: throw metadataFailure()
            val installed = ReleaseVersion.parse(installedVersion) ?: throw metadataFailure()
            if (version <= installed) return null
            val name = "Rivet-v$version.apk"
            val asset = root["assets"]?.jsonArray?.map { it.jsonObject }
                ?.singleOrNull { it["name"]?.jsonPrimitive?.contentOrNull == name }
                ?: throw UpdateFailure("This release is missing the expected Rivet update APK.")
            val size = asset["size"]?.jsonPrimitive?.longOrNull ?: throw metadataFailure()
            if (size !in 1..MAX_APK_BYTES) throw UpdateFailure("This update is empty or exceeds Rivet's 200 MB download limit.")
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: throw metadataFailure()
            if (url != assetUrl(version)) throw metadataFailure()
            return ReleaseUpdate(version, size, url)
        } catch (e: UpdateFailure) { throw e
        } catch (_: Exception) { throw metadataFailure() }
    }

    suspend fun discardPartials() = withContext(Dispatchers.IO) {
        storage.listFiles { file -> file.name.startsWith("update-") && file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }

    suspend fun download(release: ReleaseUpdate, progress: (Long, Long?) -> Unit): File {
        var staged: File? = null
        try {
            return withContext(Dispatchers.IO) {
                if (release.url != assetUrl(release.version) || release.size !in 1..MAX_APK_BYTES) throw metadataFailure()
                if (!storage.isDirectory && !storage.mkdirs()) throw UpdateFailure("Rivet couldn't prepare space for the update.")
                val target = File.createTempFile("update-", ".tmp", storage)
                staged = target
                try {
                    withTimeout(10 * 60_000L) {
                        var url = release.url.toHttpUrlOrNull() ?: throw metadataFailure()
                        var onFallback = false
                        // The GitHub chain first; the mirror only after it has spent its attempts on
                        // transport failures. The check leg above stays GitHub-only on purpose.
                        while (true) {
                        try {
                        repeat(6) {
                            val result = retrying {
                                fetch(url, "application/vnd.android.package-archive", DOWNLOAD_TRANSPORT, target,
                                            rangeFrom = target.length()) { response ->
                                if (response.code != 200 && response.code != 206) {
                                    throw UpdateFailure("GitHub answered, but not with the update file. Try again later.")
                                }
                                val body = response.body ?: throw UpdateFailure("GitHub returned an empty update.")
                                // 206 means the server honoured the Range we asked for, so the bytes
                                // are appended to the partial file. 200 means it ignored the Range
                                // and is sending the whole file: this becomes a clean restart from
                                // zero rather than a stitched-together APK.
                                val start = if (response.code == 206) resumeStart(response, target.length()) else 0L
                                val length = body.contentLength().takeIf { it >= 0 }
                                if (length != null && length + start > maxDownloadBytes) throw tooLarge()
                                var total = start
                                target.outputStream(append = start > 0).use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            total += count
                                            if (total > maxDownloadBytes) throw tooLarge()
                                            output.write(buffer, 0, count)
                                            progress(total, length?.let { start + it })
                                        }
                                    }
                                }
                                if (total == 0L || total != release.size || (length != null && total != start + length)) {
                                    throw UpdateFailure("The update download was incomplete. Try again.")
                                }
                                Fetch.Downloaded
                                        }
                                    }
                            when (result) {
                                Fetch.Downloaded -> return@withTimeout target
                                is Fetch.Redirect -> url = validatedRedirect(url, result.location)
                                else -> throw metadataFailure()
                            }
                        }
                        throw UpdateFailure("GitHub redirected the update too many times. Try again later.")
                        } catch (e: UpdateFailure) {
                            if (!e.retryable || onFallback) throw e
                            onFallback = true
                            // Two hosts can serve different bytes; a partial file from one is not a
                            // prefix of the other. Start the mirror's copy from zero.
                            target.delete()
                            url = fallbackUrl(release)
                        }
                        }
                    }
                } catch (e: CancellationException) { target.delete(); throw e
                } catch (e: UpdateFailure) {
                    // A transport failure keeps the partial file so the next attempt can resume from
                    // it. Everything else deletes it: a failed validation is not resumable.
                    if (!e.retryable) target.delete()
                    throw e
                } catch (e: Exception) { target.delete(); throw e }
            }
        } catch (e: Exception) { staged?.delete(); throw e }
    }

    // A 206 is only usable when it starts exactly where the partial file ends. A missing or
    // mismatched Content-Range restarts from zero: bytes from the wrong offset would pass the size
    // check and fail the signature check with a confusing message instead of just downloading again.
    private fun resumeStart(response: Response, requested: Long): Long {
        val start = response.header("Content-Range")
            ?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
        return if (requested > 0 && start == requested) requested else 0L
    }

    // The second road: the same filename from Rivet's own mirror, checked against the same published
    // size and then handed to UpdateApks.verify exactly as a GitHub download is. This adds a source,
    // never a weaker gate.
    private fun fallbackUrl(release: ReleaseUpdate): HttpUrl =
        (FALLBACK_BASE + release.filename).toHttpUrlOrNull() ?: throw metadataFailure()

    private fun validatedRedirect(from: HttpUrl, location: String): HttpUrl {
        val url = from.resolve(location) ?: throw metadataFailure()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || url.port != 443 || url.host !in ASSET_HOSTS) {
            throw UpdateFailure("GitHub returned an unsafe update address. Nothing was saved.")
        }
        return url
    }

    // Three attempts, transport failures only, with a growing pause between them. Anything else is
    // final: a response that arrived (even a bad one) and a validation failure would fail the same
    // way three times, and a 14 MB download that dies on one dropped connection has no business
    // being a one-shot. OkHttp's own connection retry stays off: it cannot resume, it cannot be
    // counted, and with both on there would be two owners of retrying. This one owner is testable.
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (error: UpdateFailure) {
                if (!error.retryable || attempt >= MAX_ATTEMPTS) throw error
                delay(RETRY_PAUSE_MS * attempt)
                attempt++
            }
        }
    }

    private suspend fun fetch(url: HttpUrl, accept: String, transportMessage: String, staged: File? = null,
                              rangeFrom: Long = 0L,
                              read: (Response) -> Fetch): Fetch = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        val request = Request.Builder().url(url).header("Accept", accept).get()
        if (rangeFrom > 0) request.header("Range", "bytes=$rangeFrom-")
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { cancelled.set(true); call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    UpdateFailure(transportMessage, retryable = true))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code in setOf(301, 302, 303, 307, 308)) Fetch.Redirect(it.header("Location") ?: throw metadataFailure())
                        else read(it)
                    }
                    if (continuation.isActive) continuation.resume(result) { staged?.delete() }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (e is UpdateFailure || e is CancellationException) e else UpdateFailure("Rivet couldn't finish the update download. Try again."))
                } finally {
                    // Cancellation can race a callback write. The callback owns the
                    // final cleanup too, so a late write never leaves a partial APK.
                    if (cancelled.get()) staged?.delete()
                }
            }
        })
    }

    private sealed interface Fetch {
        data class Body(val text: String) : Fetch
        data class Redirect(val location: String) : Fetch
        data object Downloaded : Fetch
        data object NoRelease : Fetch
    }

    companion object {
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 512L * 1024
        private const val MAX_ATTEMPTS = 3
        private const val FALLBACK_BASE = "https://vastraalabs.online/downloads/"
        private const val RETRY_PAUSE_MS = 400L
        // Plain words, and honest about which server said what. "Check your connection" led nowhere
        // for a user whose connection was fine: a transport failure here means this host could not
        // be reached from this device, and it may well be the host.
        private const val CHECK_TRANSPORT =
            "Rivet couldn't reach GitHub to check for updates. Try again later."
        private const val DOWNLOAD_TRANSPORT =
            "Rivet couldn't download the update file. GitHub's file server could not be reached."
        private const val LATEST = "https://api.github.com/repos/Kaiser0733/rivet/releases/latest"
        private val ASSET_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
            "github-releases.githubusercontent.com")
        private fun assetUrl(version: ReleaseVersion) =
            "https://github.com/Kaiser0733/rivet/releases/download/v$version/Rivet-v$version.apk"
        private fun metadataFailure() = UpdateFailure("GitHub's update information wasn't valid. Nothing was downloaded.")
        private fun tooLarge() = UpdateFailure("The update exceeds Rivet's 200 MB download limit. Nothing was saved.")
    }
}
