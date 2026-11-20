package com.kaiser.rivet.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ReplaySettingsStoreTest {
    @Test fun watermarkDefaultsOnAndPersistsAcrossReopening() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("rivet-replay-", ".preferences_pb", context.cacheDir)
        suspend fun withStore(block: suspend (ReplaySettingsStore) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = ReplaySettingsStore(context,
                    PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
                block(store)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        withStore { store ->
            assertTrue(store.watermark.first())
            store.setWatermark(false)
        }
        withStore { store -> assertFalse(store.watermark.first()) }
    }
}
