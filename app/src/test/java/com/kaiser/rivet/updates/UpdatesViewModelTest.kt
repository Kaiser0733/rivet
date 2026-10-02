package com.kaiser.rivet.updates

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.io.File
import java.net.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26])
class UpdatesViewModelTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var source: GitHubUpdates
    private val models = mutableListOf<UpdatesViewModel>()
    private val installed = ApkIdentity("com.kaiser.rivet", "0.11.0", 22, setOf("pin"))

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
        directory = kotlin.io.path.createTempDirectory("update-viewmodel").toFile()
        source = GitHubUpdates(directory, client)
    }
    @After fun cleanup() = runBlocking {
        models.forEach { it.cancel(); it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        Dispatchers.resetMain()
        server.shutdown()
        directory.deleteRecursively()
    }
    private fun model(signature: Boolean = true) = UpdatesViewModel(app, source,
        UpdateApks(app, { installed }, { installed.copy(versionName = "0.11.1", versionCode = 23) }, { signature }),
        "0.11.0").also { models += it }
    private fun metadata(size: Long = 4) = """{"tag_name":"v0.11.1","draft":false,"prerelease":false,"published_at":"2026-10-02T00:00:00Z","assets":[{"name":"Rivet-v0.11.1.apk","size":$size,"browser_download_url":"https://github.com/Kaiser0733/rivet/releases/download/v0.11.1/Rivet-v0.11.1.apk"}]}"""

    @Test fun checkIsManualAndMissingReleaseLeavesRecoverableCurrentState() = runBlocking {
        val vm = model()
        delay(50)
        assertEquals(0, server.requestCount)
        assertEquals(UpdateUiState.Idle, vm.state.value)
        server.enqueue(MockResponse().setResponseCode(404))
        vm.check()
        withTimeout(5000) { vm.state.first { it == UpdateUiState.Current } }
        assertEquals(1, server.requestCount)
    }

    @Test fun retainedViewModelCannotDuplicateActiveDownloadOrSavePicker() = runBlocking {
        val vm = model()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        val first = ViewModelProvider(store, factory)[UpdatesViewModel::class.java]
        server.enqueue(MockResponse().setBody(metadata(64)))
        first.check()
        withTimeout(5000) { first.state.first { it is UpdateUiState.Available } }
        server.enqueue(MockResponse().setBody("x".repeat(64)).throttleBody(1, 10, java.util.concurrent.TimeUnit.MILLISECONDS))
        first.download()
        withTimeout(5000) { first.state.first { it is UpdateUiState.Downloading && it.bytes > 0 } }
        val afterRotation = ViewModelProvider(store, factory)[UpdatesViewModel::class.java]
        assertSame(first, afterRotation)
        afterRotation.check(); afterRotation.download()
        withTimeout(5000) { first.state.first { it is UpdateUiState.AwaitingSave } }
        assertEquals(2, server.requestCount)
        assertEquals("Rivet-v0.11.1.apk", first.beginSavePicker()!!.getStringExtra(android.content.Intent.EXTRA_TITLE))
        assertNull(afterRotation.beginSavePicker())
        afterRotation.saveDocument(null)
        assertTrue(afterRotation.state.value is UpdateUiState.Available)
        assertTrue(directory.listFiles()!!.isEmpty())
        store.clear()
    }

    @Test fun failedVerificationNeverOffersSaveAndRemovesTemporaryDownload() = runBlocking {
        val vm = model(false)
        server.enqueue(MockResponse().setBody(metadata()))
        vm.check()
        withTimeout(5000) { vm.state.first { it is UpdateUiState.Available } }
        server.enqueue(MockResponse().setBody("apk!"))
        vm.download()
        withTimeout(5000) { vm.state.first { it is UpdateUiState.Error } }
        assertNull(vm.beginSavePicker())
        withTimeout(5000) { while (directory.listFiles()!!.isNotEmpty()) delay(10) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun reconstructedModelDiscardsPrivatePartialAndDoesNotCheckAutomatically() = runBlocking {
        directory.resolve("update-old.tmp").writeText("partial")
        directory.resolve("unrelated.txt").writeText("keep")
        val vm = model()
        withTimeout(5000) { while (directory.resolve("update-old.tmp").exists()) delay(10) }
        assertEquals(UpdateUiState.Idle, vm.state.value)
        assertEquals(0, server.requestCount)
        assertEquals("keep", directory.resolve("unrelated.txt").readText())
        assertNull(vm.beginSavePicker())
    }
}
