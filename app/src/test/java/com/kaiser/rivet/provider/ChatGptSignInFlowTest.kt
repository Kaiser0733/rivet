package com.kaiser.rivet.provider

import android.content.Context
import android.net.Uri
import com.kaiser.rivet.storage.HostIdStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Drives the real [runSignIn] against its real loopback listener, fires a
 * callback carrying an issued client id with a bogus code, and asserts the
 * issued id is persisted even though the token exchange must fail
 * (invalid_grant from the server, or a transport failure in a sandbox —
 * either way the pending id has to survive for the retry).
 *
 * Uses runBlocking (real time), not runTest: the sign-in coordinates with
 * real socket threads, and runTest's virtual clock would fire withTimeout
 * while parked on them.
 */
@RunWith(RobolectricTestRunner::class)
class ChatGptSignInFlowTest {

    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rivet_host_id", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun issuedClientIdSavedWhenExchangeFails() = runBlocking {
        val store = HostIdStore(context)
        val issuedId = "oaiapp_test_pending"
        val urlDeferred = CompletableDeferred<String>()
        val resultDeferred = async(Dispatchers.IO) {
            runSignIn(
                hostId = "urn:uuid:11111111-1111-4111-8111-111111111111",
                onAuthorizeUrl = { urlDeferred.complete(it) },
                clientId = CHATGPT_DYNAMIC_CLIENT_ID,
                hostIds = store,
            )
        }
        try {
            val url = withTimeout(15_000) { urlDeferred.await() }
            val redirectUri = Uri.parse(url).getQueryParameter("redirect_uri")!!
            val port = Uri.parse(redirectUri).port
            val state = Uri.parse(url).getQueryParameter("state")!!
            // Simulate the browser hitting the loopback after consent.
            withContext(Dispatchers.IO) {
                val callback = URL(
                    "http://127.0.0.1:$port/auth/callback" +
                        "?code=fake_code&state=$state&client_id=$issuedId",
                )
                (callback.openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    inputStream.use { it.readBytes() }
                    disconnect()
                }
            }
            val result = withTimeout(100_000) { resultDeferred.await() }
            assertTrue(
                "fake code must fail the exchange, got $result",
                result is ChatGptSignInResult.ServerRejected ||
                    result is ChatGptSignInResult.NetworkError,
            )
            assertEquals(issuedId, store.pendingClientId())
        } finally {
            resultDeferred.cancel()
        }
    }
}
