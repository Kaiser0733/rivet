package com.kaiser.rivet.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.kaiser.rivet.MainActivity
import com.kaiser.rivet.R
import com.kaiser.rivet.agent.AgentRunControl
import com.kaiser.rivet.agent.NotificationApprovals
import com.kaiser.rivet.agent.RunPauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class PersistentProcessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private val processes: ManagedProcesses
        get() = ManagedProcesses.forApplication(application)
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                processes.stopAll()
                stopIfIdle()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                AgentRunControl.requestPause()
                return START_NOT_STICKY
            }
            ACTION_APPROVE -> {
                NotificationApprovals.resolve(intent?.getLongExtra(EXTRA_APPROVAL_TOKEN, -1) ?: -1, true)
                return START_NOT_STICKY
            }
            ACTION_DENY -> {
                NotificationApprovals.resolve(intent?.getLongExtra(EXTRA_APPROVAL_TOKEN, -1) ?: -1, false)
                return START_NOT_STICKY
            }
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val initial = processes.foregroundState.value
        try {
            updateForegroundNotification(initial)
            processes.onForegroundServiceStarted(true)
        } catch (e: Exception) {
            processes.onForegroundServiceStarted(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (observeJob == null) observeJob = scope.launch {
            processes.foregroundState.collect { state ->
                if (!state.hasActiveWork) stopIfIdle()
                else {
                    try {
                        updateForegroundNotification(state)
                    } catch (_: Exception) {
                        // Updating the notification while already in foreground must not crash the service.
                    }
                }
            }
        } else if (!initial.hasActiveWork) {
            stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        processes.stopPersistent()
        processes.onForegroundServiceStopped()
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (processes.stopForegroundServiceIfIdle()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Rivet processes",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when Rivet is working on a turn or running a local preview."
                setShowBadge(false)
            })
        }
    }

    /** Android 15 caps dataSync work. Pause the run and let the user continue; never lose it. */
    override fun onTimeout(startId: Int) {
        AgentRunControl.requestPause(RunPauseReason.SystemTimeout)
        stopSelf(startId)
    }

    private fun updateForegroundNotification(state: ForegroundServiceState) {
        val notification = notification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (state.activeTurns > 0 && state.activePreviews == 0) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    internal fun notification(state: ForegroundServiceState): Notification {
        val turnActive = state.activeTurns > 0
        val open = PendingIntent.getActivity(this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PROCESSES, !turnActive && state.activePreviews > 0)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, REQUEST_STOP,
            Intent(this, PersistentProcessService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val statusText = when {
            turnActive -> state.runAction?.takeIf { it.isNotBlank() }
                ?: TURN_NOTIFICATION_TEXT
            state.activePreviews == 1 -> "1 local preview is running"
            else -> "${state.activePreviews} local previews are running"
        }
        val stopLabel = if (turnActive && state.activePreviews == 0) "Stop" else "Stop all"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_processes)
            .setContentTitle(state.runTitle?.takeIf { turnActive }?.let { "Rivet · $it" } ?: "Rivet")
            .setContentText(statusText)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, stopLabel, stop)
        if (turnActive && state.runStartedAt > 0) {
            builder.setWhen(state.runStartedAt).setUsesChronometer(true)
        }
        if (turnActive) {
            val pause = PendingIntent.getService(this, REQUEST_PAUSE,
                Intent(this, PersistentProcessService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            // Sensitive approvals need the app (and its own confirmation); normal
            // non-YOLO approvals can be answered here. YOLO never creates a pending one.
            val token = state.approvalToken
            if (token != null && !state.approvalDangerous) {
                fun decision(code: Int, action: String, label: String, approved: Boolean) {
                    val pending = PendingIntent.getService(this, code,
                        Intent(this, PersistentProcessService::class.java).setAction(action)
                            .putExtra(EXTRA_APPROVAL_TOKEN, token),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    builder.addAction(if (approved) android.R.drawable.ic_menu_save
                        else android.R.drawable.ic_menu_close_clear_cancel, label, pending)
                }
                decision(REQUEST_APPROVE, ACTION_APPROVE, "Approve", approved = true)
                decision(REQUEST_DENY, ACTION_DENY, "Deny", approved = false)
            }
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "rivet_local_processes"
        internal const val NOTIFICATION_ID = 110
        private const val REQUEST_OPEN = 111
        private const val REQUEST_STOP = 112
        private const val REQUEST_PAUSE = 113
        private const val REQUEST_APPROVE = 114
        private const val REQUEST_DENY = 115
        private const val EXTRA_APPROVAL_TOKEN = "approval_token"
        internal const val TURN_NOTIFICATION_TEXT = "Rivet is working — tap to return"
        internal const val ACTION_START = "com.kaiser.rivet.action.START_PROCESSES"
        internal const val ACTION_STOP_ALL = "com.kaiser.rivet.action.STOP_ALL_PROCESSES"
        internal const val ACTION_PAUSE = "com.kaiser.rivet.action.PAUSE_RUN"
        internal const val ACTION_APPROVE = "com.kaiser.rivet.action.APPROVE_STEP"
        internal const val ACTION_DENY = "com.kaiser.rivet.action.DENY_STEP"
    }
}
