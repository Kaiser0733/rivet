package com.kaiser.rivet.updates

import java.io.File
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The resilience the v1.1.1 hardening added: retries, resume, the mirror fallback, and the copy that
 * tells the truth about which server failed. Every request is rewritten to a local HTTPS server, so the
 * paths are the real ones (release-asset path versus the mirror's /downloads/ path) and the client code
 * under test is untouched.
 */
class UpdateResilienceTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var updates: GitHubUpdates

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server = MockWebServer()
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<okhttp3.Cookie>) {}
                override fun loadForRequest(url: HttpUrl) = emptyList<okhttp3.Cookie>()
            }).build()
        directory = kotlin.io.path.createTempDirectory("updates-resilience").toFile()
        updates = GitHubUpdates(directory, client, maxDownloadBytes = 64)
    }

    @After fun cleanup() { server.shutdown(); directory.deleteRecursively() }

    private fun metadata(version: String = "0.11.1", size: Long = 4): String {
        val name = "Rivet-v$version.apk"
        val url = "https://github.com/Kaiser0733/rivet/releases/download/v$version/$name"
        return "{\"tag_name\":\"v$version\",\"draft\":false,\"prerelease\":false," +
            "\"published_at\":\"2026-10-02T00:00:00Z\",\"assets\":[{\"name\":\"$name\"," +
            "\"size\":$size,\"browser_download_url\":\"$url\"}]}"
    }

    private fun release() = updates.parseRelease(metadata(), "0.11.0")!!

    private fun failure(block: suspend () -> Unit): UpdateFailure = runBlocking {
        try { block(); throw AssertionError("expected an update failure") }
        catch (e: UpdateFailure) { e }
    }

    // (1) Both legs retry a dropped connection: the server sees two requests and the call succeeds.
    @Test fun theMetadataLegRetriesOneDroppedConnection() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody(metadata()).setHeader("Content-Type", "application/json"))

        val found = updates.check("0.11.0")

        assertEquals("0.11.1", found?.version?.toString())
        assertEquals("one dropped connection is retried, not fatal", 2, server.requestCount)
    }

    @Test fun theDownloadLegRetriesOneDroppedConnection() = runBlocking {
        val release = release()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("abcd").setHeader("Content-Length", 4))

        val staged = updates.download(release) { _, _ -> }

        assertEquals("the whole file arrives after the retry", "abcd", staged.readText())
        assertEquals(2, server.requestCount)
    }

    // (2) A connection lost mid-body keeps the bytes and resumes with Range: bytes=N-.
    @Test fun aDownloadDroppedMidBodyResumesFromTheBytesOnDisk() = runBlocking {
        val release = release()
        // Two of four bytes, then the socket goes away under the reader.
        server.enqueue(MockResponse().setHeader("Content-Length", 4).setBody("ab")
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(MockResponse().setResponseCode(206).setBody("cd")
            .setHeader("Content-Range", "bytes 2-3/4"))

        val staged = updates.download(release) { _, _ -> }

        server.takeRequest()
        assertEquals("the second request asks for the missing bytes",
            "bytes=2-", server.takeRequest().getHeader("Range"))
        assertEquals("the resumed file is the fixture bytes, not a stitched surprise",
            "abcd", staged.readText())
        assertEquals(4L, staged.length())
    }

    // (3) A server that answers a Range request with 200 restarts from zero instead of stitching.
    @Test fun aTwoHundredAnswerToARangeRequestRestartsCleanly() = runBlocking {
        val release = release()
        server.enqueue(MockResponse().setHeader("Content-Length", 4).setBody("ab")
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(MockResponse().setBody("wxyz").setHeader("Content-Length", 4))

        val staged = updates.download(release) { _, _ -> }

        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("the retry still offered the Range", "bytes=2-", second.getHeader("Range"))
        assertEquals("a 200 means the whole file again, from zero", "wxyz", staged.readText())
    }

    // (5) Copy: each leg names the server that failed, and the old blame is gone from this family.
    @Test fun eachLegNamesTheServerThatFailed() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val checkError = failure { updates.check("0.11.0") }
        assertEquals("Rivet couldn't reach GitHub to check for updates. Try again later.", checkError.message)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val downloadError = failure { updates.download(release()) { _, _ -> } }

        for (message in listOfNotNull(checkError.message, downloadError.message)) {
            assertFalse("the updater no longer blames the user: $message",
                message.contains("check your connection", ignoreCase = true))
            assertTrue("and it says which server it could not reach: $message",
                message.contains("GitHub", ignoreCase = true))
        }
    }

    // (6) The bump this round ships cannot be lost in a merge.
    @Test fun theBuildDeclaresOneOneOneAsVersionCodeTwentyFive() {
        val build = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            .firstOrNull { it.isFile } ?: throw AssertionError("no build.gradle.kts from ${File(".").absolutePath}")
        val text = build.readText()
        assertTrue("versionName must be 1.1.1: $build", text.contains("versionName = \"1.1.1\""))
        assertTrue("versionCode must be 25: $build", text.contains("versionCode = 25"))
    }
}
