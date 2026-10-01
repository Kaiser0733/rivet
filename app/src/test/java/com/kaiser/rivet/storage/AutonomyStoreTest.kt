package com.kaiser.rivet.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kaiser.rivet.agent.AutonomyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomyStoreTest {
    @Test fun newAndUnknownValuesFallBackToAsk() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "autonomy-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val store = AutonomyStore(app, data)
            assertEquals(AutonomyMode.Ask, store.mode.first())
            for (value in listOf("", "unknown", "yolo", "BasicYolo ")) {
                data.edit { it[stringPreferencesKey("mode")] = value }
                assertEquals(AutonomyMode.Ask, store.mode.first())
            }
            data.edit { it[intPreferencesKey("mode")] = 2 }
            assertEquals(AutonomyMode.Ask, store.mode.first())
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
    }

    @Test fun selectedModeSurvivesStoreRecreation() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "autonomy-${UUID.randomUUID()}.preferences_pb")
        suspend fun withStore(block: suspend (AutonomyStore) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = AutonomyStore(app,
                    PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
                block(store)
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
        }
        withStore { it.setMode(AutonomyMode.BasicYolo) }
        withStore { assertEquals(AutonomyMode.BasicYolo, it.mode.first()) }
        withStore { it.setMode(AutonomyMode.Yolo) }
        withStore { assertEquals(AutonomyMode.Yolo, it.mode.first()) }
    }
}
