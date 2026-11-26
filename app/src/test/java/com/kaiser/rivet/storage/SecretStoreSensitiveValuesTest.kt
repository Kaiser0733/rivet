package com.kaiser.rivet.storage

import android.content.Context
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecretStoreSensitiveValuesTest {
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

    @Test fun enumeratesProviderAndOAuthCredentialsForRedaction() {
        assertTrue(secrets.saveApiKey("provider-one", "sk-live_12345678"))
        assertTrue(secrets.saveOAuthBundle("provider-two",
            """{"access_token":"access_12345678","refresh_token":"refresh_12345678"}"""))

        val values = secrets.sensitiveValues()

        assertTrue("sk-live_12345678" in values)
        assertTrue("access_12345678" in values)
        assertTrue("refresh_12345678" in values)
    }
}
