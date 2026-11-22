package com.kaiser.rivet.runtime

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TermuxResultReceiverTest {
    private fun resultBundle() = Bundle().apply {
        putString("stdout", "hello-rivet\n")
        putString("stderr", "")
        putString("stdout_original_length", "12")
        putString("stderr_original_length", "0")
        putString("err", "0")
        putString("exitCode", "0")
    }

    @Test fun deliveredBundleResolvesExactlyOneWaitingCommand() {
        val app = RuntimeEnvironment.getApplication()
        val action = "com.kaiser.rivet.TERMUX_RESULT.unique"
        val results = mutableListOf<TermuxOutcome?>()
        val receiver = TermuxResultReceiver(action, results::add)
        val result = resultBundle()
        receiver.onReceive(app, Intent("$action.wrong").putExtra("result", result))
        val intent = Intent(action).putExtra("result", result)
        receiver.onReceive(app, intent)
        receiver.onReceive(app, intent)
        assertEquals(1, results.size)
        assertEquals("hello-rivet\n", results.single()?.stdout)
        assertEquals(0, results.single()?.exitCode)
        assertNull(results.single()?.error)
    }

    @Test fun aRegisteredReceiverTakesTheBroadcastAndStopsAfterUnregister() {
        val app = RuntimeEnvironment.getApplication()
        val action = "com.kaiser.rivet.TERMUX_RESULT.registered"
        val results = mutableListOf<TermuxOutcome?>()
        val receiver = TermuxResultReceiver.register(app, action, results::add)
        assertTrue(shadowOf(app).registeredReceivers.any {
            it.broadcastReceiver === receiver && it.intentFilter.hasAction(action)
        })
        val intent = Intent(action).putExtra("result", resultBundle())
        // Robolectric posts delivery to the main looper (ShadowInstrumentation.postIntent
        // ends in Handler.post), so onReceive runs when that looper is idled, not inside
        // sendBroadcast. Idling keeps the real registration and matching in the test.
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
        assertEquals("hello-rivet\n", results.single()?.stdout)
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
        app.unregisterReceiver(receiver)
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
    }
}
