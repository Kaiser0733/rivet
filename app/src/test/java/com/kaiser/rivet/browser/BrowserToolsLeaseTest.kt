package com.kaiser.rivet.browser

import android.app.Application
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserToolsLeaseTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun drivingActionReleasesTheBrowserLeaseAtTheEndOfItsOwnAction() = runBlocking {
        val session = BrowserSession(app, models = null)
        val tools = BrowserTools(session)
        val lease = BrowserDrivingLease()
        val call = AgentToolCall("reset", "browser_reset", """{"reopen":false}""")

        val prepared = requireNotNull(tools.prepare(call, "worker", lease))
        prepared.execute()

        assertNull(lease.holder())
    }
}
