package com.kaiser.rivet.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registered while a Termux command waits, never declared in the manifest: the process
 * boundary allows only the AndroidX profile receiver. The filter accepts this command's
 * one-shot nonce action, so no other app can name the result it would have to forge.
 */
internal class TermuxResultReceiver(
    private val action: String,
    private val onResult: (TermuxOutcome?) -> Unit,
) : BroadcastReceiver() {
    private val delivered = AtomicBoolean(false)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != action) return
        if (!delivered.compareAndSet(false, true)) return
        onResult(outcome(intent))
    }

    @Suppress("DEPRECATION")
    private fun outcome(intent: Intent): TermuxOutcome? = runCatching {
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

    companion object {
        fun register(context: Context, action: String,
                     onResult: (TermuxOutcome?) -> Unit): TermuxResultReceiver {
            val receiver = TermuxResultReceiver(action, onResult)
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            return receiver
        }
    }
}
