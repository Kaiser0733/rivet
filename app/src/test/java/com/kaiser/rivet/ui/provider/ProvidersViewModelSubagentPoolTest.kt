package com.kaiser.rivet.ui.provider

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.storage.SubagentPoolStore
import com.kaiser.rivet.subagent.PoolEntry
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProvidersViewModelSubagentPoolTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun config(id: String, model: String) = ProviderConfig(
        id = id,
        type = ProviderType.OpenAi,
        name = "Provider $id",
        baseUrl = "https://example.invalid/v1",
        model = model,
    )

    private class FakePreferencesDataStore(
        private val onUpdate: suspend (Preferences) -> Unit = {},
    ) : DataStore<Preferences> {
        private val prefs = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = prefs

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val current = prefs.value
            val updated = transform(current)
            onUpdate(updated)
            prefs.value = updated
            return updated
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rapidPoolUpdatesSerializeCleanlyWithoutOutOfOrderClobberAndSurfaceSaveErrors() = runBlocking {
        val cfgA = config("a", "model-a")
        val cfgB = config("b", "model-b")
        val firstWriteGate = CompletableDeferred<Unit>()
        val writeCalls = AtomicInteger(0)
        val failNextWrite = AtomicBoolean(false)

        val dataStore = FakePreferencesDataStore { _ ->
            if (failNextWrite.get()) {
                throw IOException("disk full")
            }
            if (writeCalls.incrementAndGet() == 1) {
                firstWriteGate.await()
            }
        }
        val poolStore = SubagentPoolStore(dataStore, flowOf(listOf(cfgA, cfgB)))
        val vm = ProvidersViewModel(app, key, poolStore)

        try {
            // Fire two rapid updates while write #1 suspends: serialized consumer applies #1 then #2 in order.
            vm.toggleSubagentModel(cfgA)
            vm.toggleSubagentModel(cfgB)

            delay(50)
            assertEquals(1, writeCalls.get())
            assertEquals(listOf(PoolEntry("a", "model-a"), PoolEntry("b", "model-b")), vm.subagentPoolState.value.entries)

            firstWriteGate.complete(Unit)
            withTimeout(3_000) {
                while (writeCalls.get() < 2) delay(10)
            }
            assertEquals(listOf(PoolEntry("a", "model-a"), PoolEntry("b", "model-b")), poolStore.load().entries)
            assertNull(vm.subagentPoolError.value)

            // Failed save reverts optimistic state back to last persisted snapshot and exposes user-visible error.
            failNextWrite.set(true)
            vm.setSubagentAllowAgentChoice(false)
            withTimeout(3_000) {
                while (vm.subagentPoolError.value == null) delay(10)
            }
            assertNotNull(vm.subagentPoolError.value)
            assertEquals(true, vm.subagentPoolState.value.allowAgentChoice)
            assertEquals(listOf(PoolEntry("a", "model-a"), PoolEntry("b", "model-b")), vm.subagentPoolState.value.entries)
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
