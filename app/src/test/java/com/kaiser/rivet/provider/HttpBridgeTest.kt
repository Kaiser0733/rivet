package com.kaiser.rivet.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
