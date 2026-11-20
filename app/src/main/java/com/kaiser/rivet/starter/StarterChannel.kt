package com.kaiser.rivet.starter

import com.kaiser.rivet.provider.http
import com.kaiser.rivet.provider.quick
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

internal data class StarterOption(val starter: Starter, val cachedZip: File? = null)

internal class StarterChannel(
    private val cache: File,
    private val appVersion: Int,
    private val fetch: (url: String, limit: Int) -> ByteArray = ::fetchStarterBytes,
) {
    suspend fun load(bundled: List<Starter>): List<StarterOption> =
        withTimeoutOrNull(8_000) {
            withContext(Dispatchers.IO) { resolve(bundled) { coroutineContext.ensureActive() } }
        } ?: bundled.map(::StarterOption)

    internal fun resolve(bundled: List<Starter>, checkActive: () -> Unit = {}): List<StarterOption> {
        val baseline = bundled.map(::StarterOption)
        return try {
            val remote = StarterCatalog.parse(fetch(REMOTE_URL, MAX_INDEX_BYTES).decodeToString())
            remote.forEach(::validateDownload)
            val eligible = remote.filter { checkNotNull(it.minAppVersion) <= appVersion }
            val remoteIds = eligible.mapTo(HashSet()) { it.id }
            val combinedBytes = bundled.filterNot { it.id in remoteIds }.sumOf { it.bytes.toLong() } +
                eligible.sumOf { it.bytes.toLong() }
            require(combinedBytes <= StarterCatalog.MAX_TOTAL_BYTES)
            val byId = baseline.associateByTo(linkedMapOf()) { it.starter.id }
            for (option in eligible) {
                checkActive()
                byId[option.id] = StarterOption(option, cached(option))
            }
            byId.values.toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            baseline
        }
    }

    private fun validateDownload(starter: Starter) {
        val url = starter.zipUrl?.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        require(starter.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true)
        require(starter.minAppVersion != null && starter.minAppVersion > 0)
    }

    private fun cached(starter: Starter): File {
        val digest = checkNotNull(starter.sha256).lowercase()
        val destination = File(cache, "$digest.zip")
        if (destination.isFile && destination.length() == starter.bytes.toLong() &&
            destination.inputStream().use { starterSha256(it.readStarterBytes(starter.bytes)) } == digest) {
            validateArchive(destination)
            return destination
        }
        val bytes = fetch(checkNotNull(starter.zipUrl), starter.bytes)
        require(bytes.size == starter.bytes && starterSha256(bytes) == digest)
        check(cache.isDirectory || cache.mkdirs() || cache.isDirectory)
        val temporary = File.createTempFile("starter-", ".tmp", cache)
        try {
            temporary.writeBytes(bytes)
            validateArchive(temporary)
            check(temporary.renameTo(destination))
            return destination
        } finally {
            temporary.delete()
        }
    }

    private fun validateArchive(file: File) {
        ZipFile(file).use { zip ->
            for (name in listOf("index.html", "README.md")) {
                val entry = zip.getEntry(name)
                require(entry != null && !entry.isDirectory && entry.size > 0)
            }
        }
    }

    companion object {
        const val REMOTE_URL = "https://raw.githubusercontent.com/Kaiser0733/rivet/main/app/src/main/assets/starters/channel.json"
        private const val MAX_INDEX_BYTES = 64 * 1024
    }
}

internal fun starterSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun InputStream.readStarterBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (size == -1) return output.toByteArray()
        require(size <= limit - output.size())
        output.write(buffer, 0, size)
    }
}

private val starterHttp by lazy {
    http.quick().newBuilder().callTimeout(2, TimeUnit.SECONDS)
        .followRedirects(false).build()
}

private fun fetchStarterBytes(url: String, limit: Int): ByteArray {
    val request = Request.Builder().url(url).get().build()
    return starterHttp.newCall(request).execute().use { response ->
        check(response.isSuccessful)
        val body = checkNotNull(response.body)
        require(body.contentLength() <= limit)
        body.byteStream().use { it.readStarterBytes(limit) }
    }
}
