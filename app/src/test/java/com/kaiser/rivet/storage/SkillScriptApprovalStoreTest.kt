package com.kaiser.rivet.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SkillScriptApprovalStoreTest {
    @Test fun approvalsRoundTripAndDeduplicate() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "skill-scripts-${UUID.randomUUID()}.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = SkillScriptApprovalStore(app,
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))

        assertTrue(first.approved().isEmpty())
        first.approve("notes")
        first.approve("notes")
        first.approve("release-notes")
        assertEquals(setOf("notes", "release-notes"), first.approved())

        // Join, not just cancel: the reopened store needs the file released.
        firstScope.coroutineContext[Job]!!.cancelAndJoin()

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = SkillScriptApprovalStore(app,
            PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }))
        assertEquals(setOf("notes", "release-notes"), reopened.approved())
        secondScope.cancel()
    }
}
