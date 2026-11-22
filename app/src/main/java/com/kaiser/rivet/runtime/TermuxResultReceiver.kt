package com.kaiser.rivet.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/** Only the explicit one-shot PendingIntent can reach this private receiver from Termux. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callback = callbacks.remove(action) ?: return
        @Suppress("DEPRECATION")
        val outcome = runCatching {
            val data = intent.getBundleExtra("result") ?: return@runCatching null
            val fields = TermuxResultBundle(
                err = data.get("err"),
                errmsg = data.get("errmsg")?.toString(),
                stdout = data.get("stdout")?.toString(),
                stderr = data.get("stderr")?.toString(),
                exitCode = data.get("exitCode"),
                stdoutOriginalLength = data.get("stdout_original_length"),
                stderrOriginalLength = data.get("stderr_original_length"),
            )
            if (data.isEmpty) null else parseTermuxResult(fields)
        }.getOrNull()
        callback(outcome)
    }

    companion object {
        private val callbacks = ConcurrentHashMap<String, (TermuxOutcome?) -> Unit>()

        internal fun register(action: String, callback: (TermuxOutcome?) -> Unit) {
            callbacks[action] = callback
        }

        internal fun unregister(action: String) {
            callbacks.remove(action)
        }
    }
}
