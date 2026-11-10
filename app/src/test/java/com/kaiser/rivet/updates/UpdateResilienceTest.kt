package com.kaiser.rivet.updates

import java.io.File
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        server = MockWebServer()
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath).newBuilder().host("127.0.0.1").build()).build())
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
    //
    // The first version of this test was wrong: it sent a 2-byte body with Content-Length 4 and a
    // clean DISCONNECT_AT_END, which the client reads as end-of-body and rejects at its size check
    // (GitHubUpdates.kt:175) — a validation failure, correctly not retryable. No mid-body drop ever
    // happened. DISCONNECT_DURING_RESPONSE_BODY drops the socket part-way through the body, which is
    // the failure the retry and resume exist for, and a dispatcher answers the resume by reading the
    // Range it is given.
    @Test fun aDownloadDroppedMidBodyResumesFromTheBytesOnDisk() = runBlocking {
        val release = release()
        val full = "abcd"
        var resumedFrom = -1
        val ranges = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            private var first = true
            override fun dispatch(request: RecordedRequest): MockResponse {
                ranges += request.getHeader("Range")
                if (first) {
                    first = false
                    return MockResponse().setBody(full).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                // Never dereference a null Range here: an exception thrown from a dispatcher aborts
                // the connection, which the client sees as another transport failure and which hides
                // the real one. A request without a Range is answered with the whole file instead.
                val from = request.getHeader("Range")
                    ?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                    ?.coerceIn(1, full.length - 1) ?: 0
                resumedFrom = from
                return if (from == 0) {
                    MockResponse().setBody(full).setHeader("Content-Length", full.length)
                } else {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $from-${full.length - 1}/${full.length}")
                        .setBody(full.substring(from))
                }
            }
        }

        val staged = updates.download(release) { _, _ -> }

        assertTrue("the drop happened mid-body, not at the end: $resumedFrom", resumedFrom in 1..3)
        assertEquals("the second request asks for what it already has",
            "bytes=$resumedFrom-", ranges.getOrNull(1))
        assertEquals("the resumed file is the fixture bytes, not a stitched surprise",
            full, staged.readText())
        assertEquals(full.length.toLong(), staged.length())
    }

    // (3) A server that answers a Range request with 200 restarts from zero instead of stitching.
    @Test fun aTwoHundredAnswerToARangeRequestRestartsCleanly() = runBlocking {
        val release = release()
        val ranges = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            private var first = true
            override fun dispatch(request: RecordedRequest): MockResponse {
                ranges += request.getHeader("Range")
                if (first) {
                    first = false
                    return MockResponse().setBody("abcd").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                // Deliberately a 200 with a different body: a client that stitched would keep the
                // partial bytes and fail the length or content check.
                return MockResponse().setBody("wxyz").setHeader("Content-Length", 4)
            }
        }

        val staged = updates.download(release) { _, _ -> }

        assertTrue("the retry still offered the Range: ${ranges.getOrNull(1)}",
            ranges.getOrNull(1)?.startsWith("bytes=") == true)
        assertEquals("a 200 means the whole file again, from zero", "wxyz", staged.readText())
    }

    // (4) The mirror is tried only after GitHub's chain has spent its retries, and its file is held to
    // the same published size before it becomes the staged file UpdateApks.verify then inspects.
    //
    // DISCONNECT_AFTER_REQUEST rather than DISCONNECT_AT_START: the start policy drops the socket
    // before the request is read, so MockWebServer never records it and the path list came up short.
    @Test fun theMirrorIsTriedOnlyAfterGitHubsRetriesAreSpent() = runBlocking {
        val release = release()
        val paths = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path
                return if (request.path?.startsWith("/downloads/") == true) {
                    MockResponse().setBody("abcd").setHeader("Content-Length", 4)
                } else {
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
            }
        }

        val staged = updates.download(release) { _, _ -> }

        val asset = "/Kaiser0733/rivet/releases/download/v0.11.1/Rivet-v0.11.1.apk"
        assertEquals("three attempts at the release asset first", listOf(asset, asset, asset), paths.take(3))
        assertEquals("and only then the mirror, with the same filename",
            "/downloads/Rivet-v0.11.1.apk", paths.getOrNull(3))
        assertEquals("the mirror is the last resort, not a parallel attempt", 4, paths.size)
        assertEquals("the mirror's bytes are the staged file", "abcd", staged.readText())
    }

    @Test fun aMirrorFileOfTheWrongSizeIsStillRefused() = runBlocking {
        val release = release()
        val paths = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path
                return if (request.path?.startsWith("/downloads/") == true) {
                    MockResponse().setBody("abcde").setHeader("Content-Length", 5)
                } else {
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
            }
        }

        val error = failure { updates.download(release) { _, _ -> } }

        assertTrue("the mirror is held to the published size, not to what it sends: ${error.message}",
            error.message?.contains("incomplete", ignoreCase = true) == true)
        assertTrue("and nothing is left behind for verify to pick up", directory.listFiles()!!.isEmpty())
    }

    // IPv4 routes are preferred so a broken IPv6 route cannot swallow every attempt; the family is
    // still available as a later route, and everything else keeps its order.
    @Test fun ipv4RoutesAreOfferedFirstAndOrderIsOtherwiseKept() {
        fun v4(last: Byte) = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, last))
        fun v6(last: Byte) = java.net.InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, last))
        val first6 = v6(1); val first4 = v4(1); val second6 = v6(2); val second4 = v4(2)

        assertEquals("mixed list comes back IPv4-first, order otherwise preserved",
            listOf(first4, second4, first6, second6),
            ipv4First(listOf(first6, first4, second6, second4)))
        assertEquals("IPv4-only passes through", listOf(first4, second4), ipv4First(listOf(first4, second4)))
        assertEquals("IPv6-only passes through", listOf(first6, second6), ipv4First(listOf(first6, second6)))
        assertEquals("empty passes through", emptyList<java.net.InetAddress>(), ipv4First(emptyList()))
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
    @Test fun theBuildDeclaresOneThreeZeroAsVersionCodeTwentyEight() {
        val build = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            .firstOrNull { it.isFile } ?: throw AssertionError("no build.gradle.kts from ${File(".").absolutePath}")
        val text = build.readText()
        assertTrue("versionName must be 1.3.0: $build", text.contains("versionName = \"1.3.0\""))
        assertTrue("versionCode must be 28: $build", text.contains("versionCode = 28"))
    }
}
