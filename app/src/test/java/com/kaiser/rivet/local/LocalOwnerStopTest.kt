package com.kaiser.rivet.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.chat.ProviderRuntimeResult
import com.kaiser.rivet.chat.ProviderRuntimeSource
import com.kaiser.rivet.chat.StoredProviderRuntimeSource
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.storage.ProviderStore
import com.kaiser.rivet.storage.SecretStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalOwnerStopTest {
    private val entry = LocalModelEntry(
        name = "Test Qwen 3B",
        repo = "test/qwen",
        file = "qwen.gguf",
        size = 16L,
        params = "3B",
        ramFloor = 4096,
        contextMax = 4096,
        categories = listOf(LocalCategory.Coding),
        toolCalling = LocalToolCalling.Verified,
        blurb = "Test model",
    )

    private fun prepareManager(app: Application): LocalModelManager {
        val manager = LocalModelManager(app)
        val file = manager.fileFor(entry)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(entry.size.toInt()) { 1 })
        return manager
    }

    @Test
    fun stopGenerationIgnoresNonOwnerAndStopsMatchingOwnerOrThermal() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = prepareManager(app)
        val stopCalls = AtomicInteger(0)
        val enteredGenerate = CountDownLatch(1)
        val releaseGenerate = CountDownLatch(1)

        manager.runner = object : LocalModelManager.NativeRunner {
            override fun isAvailable(): Boolean = true
            override fun load(path: String, contextTokens: Int, threads: Int): LlamaModel = LlamaModel(42L)
            override fun free(model: LlamaModel) {}
            override fun stop(model: LlamaModel) {
                stopCalls.incrementAndGet()
            }
            override fun applyTemplate(
                model: LlamaModel,
                roles: List<String>,
                contents: List<String>,
                addAssistant: Boolean,
            ): String = "prompt"
            override fun generate(
                model: LlamaModel,
                prompt: String,
                maxTokens: Int,
                temperature: Float,
                topP: Float,
                grammar: String?,
                onToken: (String) -> Unit,
            ): String {
                enteredGenerate.countDown()
                releaseGenerate.await(5, TimeUnit.SECONDS)
                return "ok"
            }
        }

        val ownerA = Any()
        val ownerB = Any()
        val threadA = Thread {
            manager.generate(entry, "hi", 64, 0.7f, 0.95f, null, owner = ownerA) {}
        }
        threadA.start()
        assertTrue(enteredGenerate.await(5, TimeUnit.SECONDS))

        // Cancelling a different owner (waiting or already finished) must NOT stop ownerA.
        manager.stopGeneration(ownerB)
        assertEquals(0, stopCalls.get())

        // Stopping with the active ownerA DOES stop generation.
        manager.stopGeneration(ownerA)
        assertEquals(1, stopCalls.get())

        // Ownerless thermal stop also stops generation.
        manager.stopGeneration()
        assertEquals(2, stopCalls.get())

        releaseGenerate.countDown()
        threadA.join(5_000)

        // After ownerA exits generate, a late stopGeneration(ownerA) must be a no-op.
        manager.stopGeneration(ownerA)
        assertEquals(2, stopCalls.get())
    }

    @Test
    fun cancellingWaitingLocalClientDoesNotStopActiveClientGeneration() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = prepareManager(app)
        manager.install(entry, byo = false, checksumVerified = true)
        val config = manager.providerConfig(entry)

        val stopFlag = AtomicBoolean(false)
        val firstGenerating = CountDownLatch(1)
        val allowFirstToFinish = CountDownLatch(1)

        manager.runner = object : LocalModelManager.NativeRunner {
            override fun isAvailable(): Boolean = true
            override fun load(path: String, contextTokens: Int, threads: Int): LlamaModel = LlamaModel(99L)
            override fun free(model: LlamaModel) {}
            override fun stop(model: LlamaModel) {
                stopFlag.set(true)
            }
            override fun applyTemplate(
                model: LlamaModel,
                roles: List<String>,
                contents: List<String>,
                addAssistant: Boolean,
            ): String = contents.joinToString("\n")
            override fun generate(
                model: LlamaModel,
                prompt: String,
                maxTokens: Int,
                temperature: Float,
                topP: Float,
                grammar: String?,
                onToken: (String) -> Unit,
            ): String {
                firstGenerating.countDown()
                allowFirstToFinish.await(5, TimeUnit.SECONDS)
                onToken("Hello from active agent")
                return "Hello from active agent"
            }
        }

        val clientA = LocalProviderClient(config, manager, app)
        val clientB = LocalProviderClient(config, manager, app)
        val request = AgentRequest(
            system = "You are a test agent.",
            messages = listOf(AgentMessage(AgentRole.User, text = "ping")),
        )

        val jobA = async(Dispatchers.IO) {
            clientA.streamAgent(request, onReasoning = {}, onDelta = {})
        }
        assertTrue(firstGenerating.await(5, TimeUnit.SECONDS))

        // Start clientB while clientA holds nativeLock inside generate, then cancel clientB.
        val jobB = async(Dispatchers.IO) {
            clientB.streamAgent(request, onReasoning = {}, onDelta = {})
        }
        delay(50)
        jobB.cancel()
        runCatching { jobB.await() }

        // ClientA's native generation must NOT have been stopped by clientB's cancellation.
        assertFalse(stopFlag.get())

        allowFirstToFinish.countDown()
        val responseA = jobA.await()
        assertEquals("Hello from active agent", responseA.text)
    }

    @Test
    fun providerRuntimeSourceLoadForResolvesAnyConfiguredProviderAndDefaultsToFailure() = runBlocking {
        val defaultSource = ProviderRuntimeSource {
            ProviderRuntimeResult.Ready(
                ProviderConfig(id = "active", type = ProviderType.OpenAiCompatible, name = "Active", baseUrl = "https://example.com", model = "m"),
                "key",
            )
        }
        assertTrue(defaultSource.loadFor("other") is ProviderRuntimeResult.Failure)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val providerStore = ProviderStore(app)
        val secrets = SecretStore(app)
        val localConfig = ProviderConfig(
            id = "local-1",
            type = ProviderType.Local,
            name = "Local · Qwen",
            baseUrl = ProviderType.Local.defaultBaseUrl,
            model = entry.id,
        )
        val cloudNoKey = ProviderConfig(
            id = "cloud-no-key",
            type = ProviderType.Anthropic,
            name = "Claude",
            baseUrl = ProviderType.Anthropic.defaultBaseUrl,
            model = "claude-sonnet-4-6",
        )
        providerStore.save(localConfig)
        providerStore.save(cloudNoKey)

        val stored = StoredProviderRuntimeSource(providerStore, secrets)
        val loadedLocal = stored.loadFor("local-1")
        assertTrue(loadedLocal is ProviderRuntimeResult.Ready)
        assertEquals("local-1", (loadedLocal as ProviderRuntimeResult.Ready).config.id)

        val missingKey = stored.loadFor("cloud-no-key")
        assertTrue(missingKey is ProviderRuntimeResult.Failure)

        val unknown = stored.loadFor("does-not-exist")
        assertTrue(unknown is ProviderRuntimeResult.Failure)
    }
}
