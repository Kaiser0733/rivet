package com.kaiser.rivet.browser

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class BrowserHandoff(val url: String, val reason: String) : Exception(reason)

data class ReaderPage(
    val document: ReaderDocument,
    val rawHtml: String?,
    val httpStatus: Int,
    val nodeCount: Int,
    val hasCanvas: Boolean,
)

/** Renderer-free fetching and extraction. Never sets a User-Agent. */
class ReaderService(private val client: OkHttpClient = defaultClient()) {
    suspend fun fetch(url: String): ReaderDocument = fetchPage(url).document

    suspend fun fetchPage(url: String, visibleOnly: Boolean = true): ReaderPage = withContext(Dispatchers.IO) {
        var target = BrowserUrlPolicy.requireReadable(url)
        repeat(MAX_REDIRECTS) {
            if (BrowserAuthGuard.isAuthSurface(target)) throw BrowserHandoff(target, "identity")
            val request = Request.Builder()
                .url(target)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1")
                .get().build()
            val response = try { client.newCall(request).execute() }
            catch (_: IOException) { throw BrowserFailure("browser_network", "Rivet couldn't reach that page. Check the address and connection.") }
            response.use {
                if (it.code in 300..399) {
                    val location = it.header("Location") ?: throw BrowserFailure("browser_http", "The page redirected without a target.")
                    target = it.request.url.resolve(location)?.toString()
                        ?: throw BrowserFailure("browser_http", "The page redirected to an invalid address.")
                    if (BrowserAuthGuard.isAuthSurface(target)) throw BrowserHandoff(target, "identity")
                    return@use
                }
                if (!it.isSuccessful) throw BrowserFailure("browser_http", "The page returned HTTP ${it.code}. Use browser_open to inspect the real response or check preview_entry.")
                val finalUrl = it.request.url.toString()
                if (BrowserAuthGuard.isAuthSurface(finalUrl)) throw BrowserHandoff(finalUrl, "identity")
                val body = it.body ?: throw BrowserFailure("browser_empty", "The page returned no content.")
                val type = body.contentType()?.toString().orEmpty().lowercase(Locale.ROOT)
                val source = body.source()
                val buffer = okio.Buffer()
                var total = 0L
                while (true) {
                    val read = source.read(buffer, 8192L)
                    if (read == -1L) break
                    total += read
                    if (total > MAX_READ_BYTES) throw BrowserFailure("browser_too_large",
                    "This page is larger than Rivet's reader limit. Open it in your browser or use a smaller page.")
                }
                val bytes = buffer.readByteArray()
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val text = bytes.toString(charset)
                if (!type.contains("html") && !type.contains("xml")) {
                    val plain = text.trim()
                    return@withContext ReaderPage(
                        ReaderDocument(finalUrl, finalUrl.substringAfterLast('/'),
                            plain, ReaderExtractor.chunk(plain), needsRenderer = false),
                        rawHtml = null,
                        httpStatus = it.code,
                        nodeCount = 0,
                        hasCanvas = false,
                    )
                }
                val parsed = Jsoup.parse(text, finalUrl)
                return@withContext ReaderPage(
                    ReaderExtractor.extract(text, finalUrl, visibleOnly),
                    rawHtml = text,
                    httpStatus = it.code,
                    nodeCount = parsed.allElements.size,
                    hasCanvas = parsed.selectFirst("canvas") != null,
                )
            }
        }
        throw BrowserFailure("browser_redirect", "The page redirected too many times.")
    }

    companion object {
        const val MAX_READ_BYTES = 2L * 1024 * 1024
        private const val MAX_REDIRECTS = 6

        private fun defaultClient() = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
