package com.kaiser.rivet.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class HttpBridgeTest {

    @Test
    fun networkRequestSucceedsThroughSharedBridge() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/health")).build()
            OkHttpClient().await(request).use { response ->
                assertEquals(200, response.code)
                assertEquals("ok", response.body?.string())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun networkFailureMapsToProviderError() = runTest {
        val call = FakeCall { fake, callback ->
            callback.onFailure(fake, UnknownHostException("offline"))
        }

        try {
            call.await()
            throw AssertionError("expected network failure")
        } catch (error: ProviderError.Network) {
            assertEquals("dns", error.reason)
        }
    }

    @Test
    fun coroutineCancellationCancelsCallWithoutBecomingProviderFailure() = runTest {
        val enqueued = CompletableDeferred<Unit>()
        lateinit var callback: Callback
        val call = FakeCall { _, captured ->
            callback = captured
            enqueued.complete(Unit)
        }
        val request = async { call.await() }
        enqueued.await()

        request.cancel(CancellationException("editor closed"))
        assertTrue(call.isCanceled())
        callback.onFailure(call, IOException("Canceled"))

        try {
            request.await()
            throw AssertionError("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("editor closed", error.message)
        }
    }

    @Test
    fun duplicateCallbackCannotDoubleResumeContinuation() = runTest {
        val expected = response()
        val call = FakeCall { fake, callback ->
            callback.onResponse(fake, expected)
            callback.onFailure(fake, IOException("late callback"))
        }

        assertSame(expected, call.await())
    }

    @Test
    fun malformedRequestUrlMapsToProviderError() {
        try {
            requestBuilder("not a URL")
            throw AssertionError("expected malformed URL")
        } catch (error: ProviderError.MalformedUrl) {
            assertEquals("The base URL is not a valid URL.", error.text())
        }
    }

    @Test
    fun malformedStreamEventFailureResumesWithProviderError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("data: {}\n\n").setHeader("Content-Type", "text/event-stream"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/stream")).build()
            try {
                withTimeout(2_000) {
                    OkHttpClient().sse(request) { throw IllegalArgumentException("bad field type") }
                }
                throw AssertionError("expected invalid response")
            } catch (_: ProviderError.InvalidResponse) {
                Unit
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oversizedSseEventIsRejectedBeforeParsing() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse()
            .setBody("data: ${"x".repeat(MAX_SSE_LINE_BYTES + 1)}\n\n")
            .setHeader("Content-Type", "text/event-stream"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/stream")).build()
            try {
                OkHttpClient().sse(request) { }
                throw AssertionError("expected bounded stream failure")
            } catch (_: ProviderError.ResponseTooLarge) {
                Unit
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aggregateSseResponseIsBounded() = runBlocking {
        val server = MockWebServer()
        val event = "data: ${"x".repeat(32 * 1024)}\n\n"
        val body = buildString {
            repeat(MAX_SSE_RESPONSE_BYTES / (32 * 1024 + 8) + 2) { append(event) }
        }
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/stream")).build()
            try {
                OkHttpClient().sse(request) { }
                throw AssertionError("expected bounded stream failure")
            } catch (_: ProviderError.ResponseTooLarge) {
                Unit
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun heartbeatOnlySseStreamTimesOutAfterProgressDeadline() = runBlocking {
        val server = MockWebServer()
        val body = buildString {
            append("data: {\"delta\":1}\n\n")
            repeat(5) { append(": ping\n\n") }
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/stream")).build()
            var now = 0L
            val events = mutableListOf<String>()
            try {
                OkHttpClient().sse(
                    request = request,
                    progressDeadlineMs = SSE_PROGRESS_DEADLINE_MS,
                    clockMillis = { now.also { now += 25_000L } },
                ) { events += it }
                throw AssertionError("expected stalled heartbeat stream to time out")
            } catch (e: ProviderError.Timeout) {
                // A stream that keeps sending heartbeat lines but no data is the
                // "progress" watchdog; a socket that goes silent is "socket".
                assertEquals("progress", e.stage)
                assertEquals(listOf("{\"delta\":1}"), events)
            }
        } finally {
            server.shutdown()
        }
    }

    // A 200 that is not a stream used to be read as SSE and reported as a truncation. It is
    // either a provider error served with 200, or a normal reply on the wrong endpoint.
    @Test fun aNonStream200IsReportedForWhatItIs() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"error":{"message":"model 'x' does not exist"}}""")
            .setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}""")
            .setHeader("Content-Type", "application/json"))
        server.start()
        try {
            val client = OkHttpClient()
            val events = mutableListOf<String>()
            val error = try {
                client.sse(Request.Builder().url(server.url("/chat")).build()) { events += it }
                null
            } catch (e: ProviderError) { e }
            // The provider's own sentence is the contract here, not a classification: a 200
            // says nothing about the model, and `isModelNotFound` only reads a 400/404 body as
            // a missing model. What has to survive is the wording the provider sent.
            val failure = error as? ProviderError.ProviderMessage
            assertEquals("model 'x' does not exist", failure?.text)
            assertEquals(200, failure?.statusCode)
            assertTrue("a non-stream reply yields no events", events.isEmpty())

            events.clear()
            val reply = try {
                client.sse(Request.Builder().url(server.url("/chat")).build()) { events += it }
                null
            } catch (e: ProviderError) { e }
            assertEquals(NOT_A_STREAM, (reply as ProviderError.InvalidResponse).text())
            assertTrue(events.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    // A model that thinks longer than the progress budget and then answers must not have
    // that first token thrown away: the payload is progress, so it is accepted before the
    // deadline is judged.
    @Test
    fun aPayloadAfterALongSilenceIsAcceptedInsteadOfTimingOut() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("data: {\"delta\":1}\n")
            .setHeader("Content-Type", "text/event-stream"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/stream")).build()
            var now = 0L
            val events = mutableListOf<String>()
            val completed = OkHttpClient().sse(
                request = request,
                progressDeadlineMs = SSE_PROGRESS_DEADLINE_MS,
                clockMillis = { now.also { now += 150_000L } },
            ) { events += it }
            assertEquals(listOf("{\"delta\":1}"), events)
            assertFalse("no [DONE] was sent", completed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun realDataPayloadsRefreshSseProgressDeadline() = runBlocking {
        val server = MockWebServer()
        val body = buildString {
            repeat(4) { index ->
                append("data: {\"delta\":$index}\n\n")
            }
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/stream")).build()
            var now = 0L
            val events = mutableListOf<String>()
            val completed = OkHttpClient().sse(
                request = request,
                progressDeadlineMs = SSE_PROGRESS_DEADLINE_MS,
                clockMillis = { now.also { now += 40_000L } },
            ) { events += it }
            assertTrue(completed)
            assertEquals(4, events.size)
        } finally {
            server.shutdown()
        }
    }

    private fun response(): Response {
        val request = Request.Builder().url("https://localhost/").build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()
    }
}

private class FakeCall(
    private val onEnqueue: (FakeCall, Callback) -> Unit,
) : Call {
    private val request = Request.Builder().url("https://localhost/").build()
    private var executed = false
    private var cancelled = false

    override fun request(): Request = request

    override fun execute(): Response = error("synchronous execution is not used")

    override fun enqueue(responseCallback: Callback) {
        check(!executed)
        executed = true
        onEnqueue(this, responseCallback)
    }

    override fun cancel() {
        cancelled = true
    }

    override fun isExecuted(): Boolean = executed

    override fun isCanceled(): Boolean = cancelled

    override fun timeout(): Timeout = Timeout.NONE

    override fun clone(): Call = FakeCall(onEnqueue)
}
