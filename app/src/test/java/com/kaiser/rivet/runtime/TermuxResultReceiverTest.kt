package com.kaiser.rivet.runtime

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TermuxResultReceiverTest {
    @Test fun deliveredBundleResolvesExactlyOneWaitingCommand() {
        val app = RuntimeEnvironment.getApplication()
        val action = "com.kaiser.rivet.TERMUX_RESULT.unique"
        val results = mutableListOf<TermuxOutcome?>()
        TermuxResultReceiver.register(action, results::add)
        val receiver = TermuxResultReceiver()
        val result = Bundle().apply {
            putString("stdout", "hello-rivet\n")
            putString("stderr", "")
            putString("stdout_original_length", "12")
            putString("stderr_original_length", "0")
            putString("err", "0")
            putString("exitCode", "0")
        }
        receiver.onReceive(app, Intent("$action.wrong").putExtra("result", result))
        val intent = Intent(action).putExtra("result", result)
        receiver.onReceive(app, intent)
        receiver.onReceive(app, intent)
        assertEquals(1, results.size)
        assertEquals("hello-rivet\n", results.single()?.stdout)
        assertEquals(0, results.single()?.exitCode)
        assertNull(results.single()?.error)
    }
}
