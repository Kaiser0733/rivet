package com.kaiser.rivet.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.subagent.PoolEntry
import com.kaiser.rivet.subagent.SubagentPoolState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SubagentPoolStoreTest {
    private fun config(id: String, model: String) = ProviderConfig(
        id = id,
        type = ProviderType.OpenAi,
        name = "Provider $id",
        baseUrl = "https://example.invalid/v1",
        model = model,
    )

    private suspend fun <T> withStore(
        configs: MutableStateFlow<List<ProviderConfig>>,
        block: suspend (SubagentPoolStore) -> T,
    ): T {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "subagents-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            block(SubagentPoolStore(dataStore, configs))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun roundTripsTheAllowListAndFixedChoice() = runBlocking {
        val configs = MutableStateFlow(listOf(config("c1", "model-1"), config("c2", "model-2")))
        withStore(configs) { store ->
            val state = SubagentPoolState(
                allowAgentChoice = false,
                entries = listOf(PoolEntry("c1", "model-1")),
                fixed = PoolEntry("c2", "model-2"),
            )
            store.save(state)
            assertEquals(state, store.state.first())
        }
    }

    @Test
    fun staleConfigsAndChangedModelsAreFilteredAtReadWithoutRewritingTheStore() = runBlocking {
        val configs = MutableStateFlow(listOf(config("c1", "model-1"), config("c2", "model-2")))
        withStore(configs) { store ->
            store.save(SubagentPoolState(
                entries = listOf(
                    PoolEntry("c1", "model-1"),
                    PoolEntry("c2", "model-2"),
                    PoolEntry("gone", "old"),
                ),
                fixed = PoolEntry("c2", "model-2"),
            ))
            assertEquals(listOf(PoolEntry("c1", "model-1"), PoolEntry("c2", "model-2")),
                store.state.first().entries)

            // Removed provider and changed model are both stale; toggle choice survives.
            configs.value = listOf(config("c2", "model-3"))
            val filtered = store.state.first()
            assertEquals(emptyList<PoolEntry>(), filtered.entries)
            assertNull(filtered.fixed)
            assertEquals(true, filtered.allowAgentChoice)

            // The stale rows were not erased: restoring the exact config makes them live again.
            configs.value = listOf(config("c1", "model-1"), config("c2", "model-2"))
            assertEquals(listOf(PoolEntry("c1", "model-1"), PoolEntry("c2", "model-2")),
                store.state.first().entries)
        }
    }

    @Test
    fun theDefaultStateIsAnEmptyAgentChoicePool() = runBlocking {
        val configs = MutableStateFlow(listOf(config("c1", "model-1")))
        withStore(configs) { store ->
            assertEquals(SubagentPoolState(), store.state.first())
        }
    }
}
