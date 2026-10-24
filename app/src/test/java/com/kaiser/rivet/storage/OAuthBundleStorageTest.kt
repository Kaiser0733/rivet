package com.kaiser.rivet.storage

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class OAuthBundleStorageTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private lateinit var secrets: SecretStore

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rivet_secret_ciphertext_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        secrets = SecretStore(context, key)
    }

    @After fun teardown() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rivet_secret_ciphertext_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun bundleRoundTrip() {
        val json = """{"access_token":"abc","refresh_token":"def"}"""
        assertTrue(secrets.saveOAuthBundle("p1", json))
        assertEquals(json, secrets.oauthBundle("p1"))
    }

    @Test fun bundleAndApiKeyCoexist() {
        assertTrue(secrets.saveApiKey("p1", "sk-test"))
        assertTrue(secrets.saveOAuthBundle("p1", """{"a":1}"""))
        assertEquals("sk-test", secrets.apiKey("p1"))
        assertEquals("""{"a":1}""", secrets.oauthBundle("p1"))
        // Clearing one leaves the other intact.
        secrets.clearOAuthBundle("p1")
        assertNull(secrets.oauthBundle("p1"))
        assertEquals("sk-test", secrets.apiKey("p1"))
        secrets.clearApiKey("p1")
        assertNull(secrets.apiKey("p1"))
    }

    @Test fun missingBundleIsNull() {
        assertNull(secrets.oauthBundle("nope"))
    }

    @Test fun corruptedBundleIsNull() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rivet_secret_ciphertext_v1", Context.MODE_PRIVATE)
            .edit().putString("p1:oauth", "v1:not-valid!!").commit()
        assertNull(secrets.oauthBundle("p1"))
    }

    @Test fun bundleIsolatedPerProvider() {
        assertTrue(secrets.saveOAuthBundle("p1", "one"))
        assertNull(secrets.oauthBundle("p2"))
    }

    @Test fun crossKeyIsolation() {
        // A different key cannot decrypt; save with key A then read with key B.
        val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val context = RuntimeEnvironment.getApplication() as Context
        val other = SecretStore(context, otherKey)
        assertTrue(secrets.saveOAuthBundle("p1", "secret"))
        assertNull(other.oauthBundle("p1"))
        assertFalse(other.hasApiKey("p1"))
    }
}
