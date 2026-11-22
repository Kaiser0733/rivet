package com.kaiser.rivet.ui.phone

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.kaiser.rivet.runtime.PhoneCaptureEvent
import com.kaiser.rivet.runtime.PhoneCaptureResult
import com.kaiser.rivet.runtime.PhoneCaptureState
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PhoneCaptureCoordinator(context: Context) {
    private val app = context.applicationContext
    var consentLauncher: ((Intent) -> Boolean)? = null
    var rivetVisible = false
    var rivetResumed = false
    // Overridable so a test can prove the raise happens once without starting an activity.
    var bringToFront: () -> Unit = {
        runCatching {
            app.startActivity(Intent(app, com.kaiser.rivet.MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        Unit
    }

    private var active: Capture? = null
    private var awaitingActivityResult = false

    suspend fun capture(onStatus: (String) -> Unit): PhoneCaptureResult = withContext(Dispatchers.Main.immediate) {
        val launcher = consentLauncher ?: return@withContext error("phone_shot_unavailable")
        if (active != null || awaitingActivityResult) return@withContext error("phone_shot_busy")
        val notifications = app.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled() ||
            notifications.getNotificationChannel(PhoneCaptureService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return@withContext error("phone_shot_notifications_off")
        }
        val projection = app.getSystemService(MediaProjectionManager::class.java)
            ?: return@withContext error("phone_shot_unavailable")
        val request = Capture(UUID.randomUUID().toString(), onStatus)
        request.advance(PhoneCaptureEvent.Request)
        active = request
        try {
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                projection.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else projection.createScreenCaptureIntent()
            awaitingActivityResult = true
            if (!launcher(intent)) {
                val failure = awaitForegroundLaunch(intent, launcher, request)
                if (failure != null) {
                    awaitingActivityResult = false
                    return@withContext error(failure)
                }
            }
            val beforeArm = withTimeoutOrNull(180_000) {
                select {
                    request.result.onAwait { it }
                    request.armed.onAwait { null }
                }
            }
            beforeArm ?: if (request.armed.isCompleted) {
                withTimeoutOrNull(PhoneCaptureService.ACTION_TIMEOUT_MS + 15_000) { request.result.await() }
                    ?: error("phone_shot_timeout")
            } else error("phone_shot_timeout")
        } finally {
            if (active === request) active = null
            if (request.startedService) app.stopService(Intent(app, PhoneCaptureService::class.java))
        }
    }

    /**
     * A chained tool can call phone_shot while a Custom Tab owns the screen, so the
     * activity is paused and the consent launcher refuses. Wait for the resume instead
     * of failing instantly; only a bounded expiry reports needs_foreground.
     */
    private suspend fun awaitForegroundLaunch(
        intent: Intent,
        launcher: (Intent) -> Boolean,
        request: Capture,
    ): String? {
        var accepted = false
        var refusedWhileResumed = false
        var raised = false
        request.onStatus(PHONE_SHOT_WAITING_FOREGROUND)
        val completed = withTimeoutOrNull(PHONE_SHOT_FOREGROUND_WAIT_MS) {
            while (true) {
                if (shouldRaiseRivet(rivetResumed, raised)) {
                    raised = true
                    bringToFront()
                }
                if (rivetResumed) {
                    if (launcher(intent)) {
                        accepted = true
                        return@withTimeoutOrNull true
                    }
                    refusedWhileResumed = true
                    return@withTimeoutOrNull false
                }
                delay(200)
            }
        }
        return phoneShotFailureCode(accepted, refusedWhileResumed, completed == null)
    }

    fun consent(resultCode: Int, data: Intent?) {
        awaitingActivityResult = false
        val request = active ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (request.advance(PhoneCaptureEvent.Deny)) request.result.complete(error("phone_shot_denied"))
            return
        }
        if (!request.advance(PhoneCaptureEvent.Consent) || !request.state.canStartService) return
        val service = PhoneCaptureService.intent(app, PhoneCaptureService.ARM, request.id)
            .putExtra(PhoneCaptureService.CONSENT, data)
        try {
            request.startedService = app.startForegroundService(service) != null
            if (!request.startedService) finish(request.id, error("phone_shot_unavailable"))
        } catch (_: Exception) {
            finish(request.id, error("phone_shot_unavailable"))
        }
    }

    fun canArm(id: String): Boolean = active?.let { it.id == id && it.state.canStartService && !it.notificationPosted } == true

    fun notificationPosted(id: String) {
        val request = active?.takeIf { it.id == id } ?: return
        request.notificationPosted = true
        request.armed.complete(Unit)
        request.onStatus("phone_shot_armed")
    }

    fun beginFrame(id: String): Boolean = active?.takeIf { it.id == id && it.notificationPosted }
        ?.advance(PhoneCaptureEvent.Capture) == true

    fun finish(id: String, result: PhoneCaptureResult) {
        val request = active?.takeIf { it.id == id } ?: return
        val event = if (result.error == null) PhoneCaptureEvent.Finish else PhoneCaptureEvent.Cancel
        if (request.advance(event)) request.result.complete(result)
    }

    private fun error(code: String) = PhoneCaptureResult(error = code)

    private class Capture(val id: String, val onStatus: (String) -> Unit) {
        val result = CompletableDeferred<PhoneCaptureResult>()
        val armed = CompletableDeferred<Unit>()
        var state = PhoneCaptureState.Idle
            private set
        var startedService = false
        var notificationPosted = false

        fun advance(event: PhoneCaptureEvent): Boolean {
            state = state.transition(event) ?: return false
            return true
        }
    }
}
