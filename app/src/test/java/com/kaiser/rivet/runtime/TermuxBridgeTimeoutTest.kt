package com.kaiser.rivet.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TermuxBridgeTimeoutTest {
    @Test fun lateCallbackCanBeReadWithoutDispatchingTheCommandAgain() = runTest {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = TermuxCommand.PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = TermuxCommand.PACKAGE }
        })
        val starts = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? {
                starts += service
                return service.component
            }
        }
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bridge = TermuxBridge(context)
        try {
            val start = AgentToolCall("start", "termux_command", """{"command":"echo test","timeout_ms":1}""")
            val initial = bridge.prepare(start, null, "owner") { error("No grant") }.execute()
            assertFalse(initial.error)
            val wire = Json.parseToJsonElement(initial.content).jsonObject
            assertEquals("pending", wire["status"]!!.jsonPrimitive.content)
            val id = wire["operation_id"]!!.jsonPrimitive.content
            val lookup = AgentToolCall("lookup", "termux_command", """{"operation_id":"$id"}""")
            val wrongOwner = bridge.prepare(lookup, null, "other") { error("No grant") }
            assertTrue(wrongOwner.execute().error)

            @Suppress("DEPRECATION")
            val reply = starts.single().getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
            val action = shadowOf(reply).savedIntent.action
            TermuxResultReceiver().onReceive(context, Intent(action).putExtra("result", Bundle().apply {
                putString("stdout", "late reply")
                putInt("exitCode", 0)
            }))

            val poll = bridge.prepare(lookup, null, "owner") { error("No grant") }
            assertEquals(AgentToolEffect.ReadOnly, poll.effect)
            assertTrue(poll.execute().content.contains("late reply"))
            assertEquals(1, starts.size)
        } finally {
            bridge.close()
            Dispatchers.resetMain()
        }
    }
}
