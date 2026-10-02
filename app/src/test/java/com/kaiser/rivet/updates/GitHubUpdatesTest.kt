package com.kaiser.rivet.updates

import java.io.File
import java.net.InetAddress
import java.net.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GitHubUpdatesTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var updates: GitHubUpdates

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("secret").value("provider-cookie").domain("github.com").build())
            }).authenticator { _, response -> response.request.newBuilder().header("Authorization", "provider-key").build() }
            .build()
        directory = kotlin.io.path.createTempDirectory("updates").toFile()
        updates = GitHubUpdates(directory, client, maxDownloadBytes = 64)
    }
    @After fun cleanup() { server.shutdown(); directory.deleteRecursively() }

    private fun metadata(version: String = "0.11.1", name: String = "Rivet-v$version.apk",
                         size: Long = 4, url: String = "https://github.com/Kaiser0733/rivet/releases/download/v$version/$name",
                         flags: String = "\"draft\":false,\"prerelease\":false") =
        """{"tag_name":"v$version",$flags,"published_at":"2026-10-02T00:00:00Z","assets":[{"name":"$name","size":$size,"browser_download_url":"$url"}]}"""

    @Test fun newerReleaseRequiresExactAssetAndStrictSemver() {
        assertEquals("0.11.1", updates.parseRelease(metadata(), "0.11.0")!!.version.toString())
        assertNull(updates.parseRelease(metadata(), "0.11.1"))
        assertNull(updates.parseRelease(metadata(), "0.12.0"))
        assertNull(updates.parseRelease(metadata(flags = "\"draft\":true,\"prerelease\":false"), "0.11.0"))
        assertNull(updates.parseRelease(metadata(flags = "\"draft\":false,\"prerelease\":true"), "0.11.0"))
        listOf("0.11.1-beta", "0.011.1", "0.11", "0.11.1/evil").forEach {
            assertFailure { updates.parseRelease(metadata(it), "0.11.0") }
        }
        assertFailure { updates.parseRelease(metadata(name = "app-debug.apk"), "0.11.0") }
        assertFailure { updates.parseRelease(metadata(url = "http://github.com/Kaiser0733/rivet/file.apk"), "0.11.0") }
        assertFailure { updates.parseRelease(metadata(url = "https://evil.invalid/Rivet-v0.11.1.apk"), "0.11.0") }
        assertFailure { updates.parseRelease(metadata(size = 201L * 1024 * 1024), "0.11.0") }
        assertFailure { updates.parseRelease(metadata().replace("\"assets\":[", "\"other_assets\":["), "0.11.0") }
    }

    @Test fun latestEndpointAndDownloadHaveNoProviderCredentials() = runTest {
        server.enqueue(MockResponse().setBody(metadata()))
        val release = updates.check("0.11.0")!!
        val request = server.takeRequest()
        assertEquals("/repos/Kaiser0733/rivet/releases/latest", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("x-api-key"))
        assertNull(request.getHeader("x-goog-api-key"))
        server.enqueue(MockResponse().setBody("apk!"))
        val progress = mutableListOf<Pair<Long, Long?>>()
        val file = updates.download(release) { count, total -> progress += count to total }
        assertEquals("apk!", file.readText())
        val download = server.takeRequest()
        assertEquals("/Kaiser0733/rivet/releases/download/v0.11.1/Rivet-v0.11.1.apk", download.path)
        assertNull(download.getHeader("Authorization"))
        assertNull(download.getHeader("Cookie"))
        assertEquals(4L to 4L, progress.last())
        file.delete()
    }

    @Test fun downloadsAreBoundedEvenWithoutAnHonestLengthAndFailuresCleanTemps() = runTest {
        val release = updates.parseRelease(metadata(), "0.11.0")!!
        server.enqueue(MockResponse().setChunkedBody("x".repeat(65), 8))
        assertSuspendFailure { updates.download(release) { _, _ -> } }
        assertTrue(directory.listFiles()!!.isEmpty())
        server.enqueue(MockResponse().setBody("x"))
        assertSuspendFailure { updates.download(release) { _, _ -> } }
        server.enqueue(MockResponse().setResponseCode(500))
        assertSuspendFailure { updates.download(release) { _, _ -> } }
        server.enqueue(MockResponse().setBody(""))
        assertSuspendFailure { updates.download(release) { _, _ -> } }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun redirectCannotEscapeOfficialHttpsAssetHosts() = runTest {
        val release = updates.parseRelease(metadata(), "0.11.0")!!
        for (target in listOf("http://github.com/file", "https://evil.invalid/file", "https://user:pass@github.com/file")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
            assertSuspendFailure { updates.download(release) { _, _ -> } }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cancellingDownloadRemovesPrivatePartialFile() = runBlocking {
        val release = updates.parseRelease(metadata(size = 64), "0.11.0")!!
        server.enqueue(MockResponse().setBody("x".repeat(64)).throttleBody(1, 50, java.util.concurrent.TimeUnit.MILLISECONDS))
        val began = CompletableDeferred<Unit>()
        val job = launch { updates.download(release) { _, _ -> began.complete(Unit) } }
        withTimeout(5000) { began.await() }
        job.cancelAndJoin()
        withTimeout(5000) { while (directory.listFiles()!!.isNotEmpty()) delay(10) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    private fun assertFailure(block: () -> Any?) {
        try { block(); fail("Expected update rejection") } catch (_: UpdateFailure) {}
    }
    private suspend fun assertSuspendFailure(block: suspend () -> Any?) {
        try { block(); fail("Expected update rejection") } catch (_: UpdateFailure) {}
    }
}
