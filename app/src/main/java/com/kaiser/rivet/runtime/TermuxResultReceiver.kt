package com.kaiser.rivet.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val receive = awaiting.remove(action) ?: return
        receive(outcome(intent))
    }

    @Suppress("DEPRECATION")
    private fun outcome(intent: Intent): TermuxOutcome = runCatching {
        val data = intent.getBundleExtra("result") ?: return@runCatching unreadable()
        val fields = TermuxResultBundle(
            err = data.get("err"),
            errmsg = data.get("errmsg")?.toString(),
            stdout = data.get("stdout")?.toString(),
            stderr = data.get("stderr")?.toString(),
            exitCode = data.get("exitCode"),
            stdoutOriginalLength = data.get("stdout_original_length"),
            stderrOriginalLength = data.get("stderr_original_length"),
        )
        if (data.isEmpty) unreadable() else parseTermuxResult(fields)
    }.getOrElse { unreadable() }

    private fun unreadable() = TermuxOutcome(error = TERMUX_NO_RESULT)

    companion object {
        private val awaiting = ConcurrentHashMap<String, (TermuxOutcome) -> Unit>()

        internal fun register(action: String, onResult: (TermuxOutcome) -> Unit) {
            check(awaiting.putIfAbsent(action, onResult) == null)
        }

        internal fun unregister(action: String) {
            awaiting.remove(action)
        }
    }
}
