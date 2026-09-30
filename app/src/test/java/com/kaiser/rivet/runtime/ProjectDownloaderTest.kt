package com.kaiser.rivet.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolEffect
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AgentToolExecutorTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDownloaderTest {
    private fun server(): Pair<MockWebServer, OkHttpClient> {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        return server to client
    }

    @Test fun streamsHttpsBytesAndDoesNotForwardCredentialsOrCookies() {
        val (server, client) = server()
        val folder = Files.createTempDirectory("rivet-download-test").toFile()
        try {
            val stale = File(folder, "rivet-download-stale.tmp").apply { writeText("partial") }
            val content = "hello static file".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(content)))
            val staged = kotlinx.coroutines.runBlocking {
                ProjectDownloader(folder, client).download(server.url("/icon.svg").toString())
            }
            staged.use {
                assertEquals(content.size.toLong(), staged.size)
                assertEquals(sha256(content), staged.sha256)
                assertEquals(content.toList(), staged.file.readBytes().toList())
            }
            assertFalse(stale.exists())
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals(null, request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("Cookie"))
            assertEquals(0, folder.listFiles().orEmpty().size)
        } finally {
            server.shutdown()
            folder.deleteRecursively()
        }
    }

    @Test fun revalidatesRedirectsAndRejectsDowngradeOrExcessiveRedirects() {
        val (server, client) = server()
        val folder = Files.createTempDirectory("rivet-redirect-test").toFile()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
            server.enqueue(MockResponse().setBody("ok"))
            val staged = kotlinx.coroutines.runBlocking {
                ProjectDownloader(folder, client).download(server.url("/start").toString())
            }
            staged.close()
            assertEquals(2, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(302)
                .addHeader("Location", "http://example.invalid/file"))
            val downgrade = downloadFailure {
                kotlinx.coroutines.runBlocking {
                    ProjectDownloader(folder, client).download(server.url("/downgrade").toString())
                }
            }
            assertEquals("redirect_blocked", downgrade.code)

            repeat(6) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop-$it")) }
            val overflow = downloadFailure {
                kotlinx.coroutines.runBlocking {
                    ProjectDownloader(folder, client).download(server.url("/loop").toString())
                }
            }
            assertEquals("redirect_blocked", overflow.code)
        } finally {
            server.shutdown()
            folder.deleteRecursively()
        }
    }

    @Test fun aSecondDownloaderDoesNotRemoveAnUnconsumedStageFromThisProcess() {
        val (server, client) = server()
        val folder = Files.createTempDirectory("rivet-download-concurrent-stage").toFile()
        try {
            server.enqueue(MockResponse().setBody("first"))
            server.enqueue(MockResponse().setBody("second"))
            val first = kotlinx.coroutines.runBlocking {
                ProjectDownloader(folder, client).download(server.url("/first").toString())
            }
            val second = kotlinx.coroutines.runBlocking {
                ProjectDownloader(folder, client).download(server.url("/second").toString())
            }
            assertTrue(first.file.exists())
            assertEquals("first", first.file.readText())
            assertEquals("second", second.file.readText())
            first.close()
            second.close()
        } finally {
            server.shutdown()
            folder.deleteRecursively()
        }
    }

    @Test fun rejectsCleartextCredentialsBadChecksumsAndKnownOversizeBeforeSaving() {
        val (server, client) = server()
        val folder = Files.createTempDirectory("rivet-download-limits").toFile()
        try {
            val downloader = ProjectDownloader(folder, client)
            assertEquals("invalid_url", downloadFailure {
                kotlinx.coroutines.runBlocking { downloader.download("http://example.test/file") }
            }.code)
            assertEquals("invalid_url", downloadFailure {
                kotlinx.coroutines.runBlocking { downloader.download("https://user:pass@example.test/file") }
            }.code)
            assertEquals("invalid_checksum", downloadFailure {
                kotlinx.coroutines.runBlocking { downloader.download(server.url("/file").toString(), "bad") }
            }.code)
            server.enqueue(MockResponse().setBody("small")
                .setHeader("Content-Length", ProjectDownloader.MAX_DOWNLOAD_BYTES + 1))
            assertEquals("download_too_large", downloadFailure {
                kotlinx.coroutines.runBlocking { downloader.download(server.url("/large").toString()) }
            }.code)
            assertEquals(0, folder.listFiles().orEmpty().size)
        } finally {
            server.shutdown()
            folder.deleteRecursively()
        }
    }

    @Test fun downloadToolUsesSafMutationContractAndReturnsConfirmedHash() {
        val (server, client) = server()
        val folder = Files.createTempDirectory("rivet-download-tool").toFile()
        try {
            val content = "downloaded bytes".toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(content)))
            val workspace = AgentToolExecutorTest.FakeWorkspace()
            var safChecks = 0
            val executor = AgentToolExecutor(workspace,
                requireSafCurrent = { safChecks++ },
                downloader = ProjectDownloader(folder, client))
            val prepared = kotlinx.coroutines.runBlocking {
                executor.prepare(AgentToolCall("download", "download_file",
                    """{"url":"${server.url("/asset.svg")}","path":"assets/asset.svg"}"""))
            }
            assertEquals(AgentToolEffect.Download, prepared.effect)
            assertEquals(16 * 1024, prepared.resultContentLimitBytes)
            assertTrue(prepared.approval != null)
            val result = kotlinx.coroutines.runBlocking { prepared.execute() }
            val value = Json.parseToJsonElement(result.content).jsonObject
            assertFalse(result.error)
            assertEquals(2, safChecks)
            assertEquals(content.toList(), workspace.downloadedBytes!!.toList())
            assertEquals("assets/asset.svg", value["path"]!!.jsonPrimitive.content)
            assertEquals(sha256(content), value["sha256"]!!.jsonPrimitive.content)
            assertEquals(content.size.toString(), value["size"]!!.jsonPrimitive.content)
            assertEquals("true", value["created"]!!.jsonPrimitive.content)
        } finally {
            server.shutdown()
            folder.deleteRecursively()
        }
    }

    @Test fun streamingLimitCatchesMissingOrFalseContentLength() {
        val folder = Files.createTempDirectory("rivet-download-stream-limit").toFile()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val body = object : ResponseBody() {
                    private val source: BufferedSource = object : Source {
                        private var remaining = ProjectDownloader.MAX_DOWNLOAD_BYTES + 1
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (remaining == 0L) return -1
                            val size = minOf(byteCount, remaining, 64 * 1024L).toInt()
                            sink.write(ByteArray(size) { 'x'.code.toByte() })
                            remaining -= size
                            return size.toLong()
                        }
                        override fun timeout(): Timeout = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength(): Long = -1
                    override fun source(): BufferedSource = source
                }
                Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build()
            }.build()
            val result = downloadFailure {
                kotlinx.coroutines.runBlocking {
                    ProjectDownloader(folder, client).download("https://example.test/stream")
                }
            }
            assertEquals("download_too_large", result.code)
            assertEquals(0, folder.listFiles().orEmpty().size)
        } finally { folder.deleteRecursively() }
    }

    private fun downloadFailure(block: () -> Any): DownloadFailure {
        try { block() } catch (error: DownloadFailure) { return error }
        throw AssertionError("expected DownloadFailure")
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
