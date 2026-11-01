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
        if (intent?.action == ACTION_STOP_ALL) {
            processes.stopAll()
            stopIfIdle()
            return START_NOT_STICKY
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

    private fun updateForegroundNotification(state: ForegroundServiceState) {
        val notification = notification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
            turnActive -> TURN_NOTIFICATION_TEXT
            state.activePreviews == 1 -> "1 local preview is running"
            else -> "${state.activePreviews} local previews are running"
        }
        val stopLabel = if (turnActive && state.activePreviews == 0) "Stop" else "Stop all"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_processes)
            .setContentTitle("Rivet")
            .setContentText(statusText)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, stopLabel, stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rivet_local_processes"
        internal const val NOTIFICATION_ID = 110
        private const val REQUEST_OPEN = 111
        private const val REQUEST_STOP = 112
        internal const val TURN_NOTIFICATION_TEXT = "Rivet is working — tap to return"
        internal const val ACTION_START = "com.kaiser.rivet.action.START_PROCESSES"
        internal const val ACTION_STOP_ALL = "com.kaiser.rivet.action.STOP_ALL_PROCESSES"
    }
}
