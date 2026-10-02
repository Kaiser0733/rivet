package com.kaiser.rivet.updates

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

internal class UpdateFailure(message: String) : Exception(message)

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
        val result = fetch(LATEST.toHttpUrlOrNull()!!, "application/vnd.github+json") { response ->
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
                        repeat(6) {
                            val result = fetch(url, "application/vnd.android.package-archive", target) { response ->
                                if (!response.isSuccessful) throw UpdateFailure("Rivet couldn't download the update. Try again later.")
                                val body = response.body ?: throw UpdateFailure("GitHub returned an empty update.")
                                val length = body.contentLength().takeIf { it >= 0 }
                                if (length != null && length > maxDownloadBytes) throw tooLarge()
                                var total = 0L
                                target.outputStream().use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            total += count
                                            if (total > maxDownloadBytes) throw tooLarge()
                                            output.write(buffer, 0, count)
                                            progress(total, length)
                                        }
                                    }
                                }
                                if (total == 0L || total != release.size || (length != null && total != length)) {
                                    throw UpdateFailure("The update download was incomplete. Try again.")
                                }
                                Fetch.Downloaded
                            }
                            when (result) {
                                Fetch.Downloaded -> return@withTimeout target
                                is Fetch.Redirect -> url = validatedRedirect(url, result.location)
                                else -> throw metadataFailure()
                            }
                        }
                        throw UpdateFailure("GitHub redirected the update too many times. Try again later.")
                    }
                } catch (e: CancellationException) { target.delete(); throw e
                } catch (e: Exception) { target.delete(); throw e }
            }
        } catch (e: Exception) { staged?.delete(); throw e }
    }

    private fun validatedRedirect(from: HttpUrl, location: String): HttpUrl {
        val url = from.resolve(location) ?: throw metadataFailure()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || url.port != 443 || url.host !in ASSET_HOSTS) {
            throw UpdateFailure("GitHub returned an unsafe update address. Nothing was saved.")
        }
        return url
    }

    private suspend fun fetch(url: HttpUrl, accept: String, staged: File? = null,
                              read: (Response) -> Fetch): Fetch = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        val call = client.newCall(Request.Builder().url(url).header("Accept", accept).get().build())
        continuation.invokeOnCancellation { cancelled.set(true); call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    UpdateFailure("Rivet couldn't reach GitHub. Check your connection and try again."))
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
        private const val LATEST = "https://api.github.com/repos/Kaiser0733/rivet/releases/latest"
        private val ASSET_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
            "github-releases.githubusercontent.com")
        private fun assetUrl(version: ReleaseVersion) =
            "https://github.com/Kaiser0733/rivet/releases/download/v$version/Rivet-v$version.apk"
        private fun metadataFailure() = UpdateFailure("GitHub's update information wasn't valid. Nothing was downloaded.")
        private fun tooLarge() = UpdateFailure("The update exceeds Rivet's 200 MB download limit. Nothing was saved.")
    }
}
