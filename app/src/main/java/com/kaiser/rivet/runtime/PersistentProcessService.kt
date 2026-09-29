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
import com.kaiser.rivet.RivetApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class PersistentProcessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private val processes: ManagedProcesses
        get() = (application as RivetApplication).managedProcesses
    private var latestStartId = 0
    private var idleStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP_ALL) {
            processes.stopAll()
            scheduleStopIfIdle()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        idleStopJob?.cancel()
        try {
            updateForegroundNotification(processes.activePersistentCount())
            processes.onForegroundServiceStarted(true)
        } catch (e: Exception) {
            processes.onForegroundServiceStarted(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (observeJob == null) observeJob = scope.launch {
            processes.processes.collect {
                val count = processes.activePersistentCount()
                if (count == 0) scheduleStopIfIdle()
                else {
                    idleStopJob?.cancel()
                    updateForegroundNotification(count)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        idleStopJob?.cancel()
        scope.cancel()
        processes.stopPersistent()
        processes.onForegroundServiceStopped()
        super.onDestroy()
    }

    private fun scheduleStopIfIdle() {
        if (processes.activePersistentCount() != 0) return
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            delay(IDLE_STOP_GRACE_MS)
            if (processes.activePersistentCount() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                processes.onForegroundServiceStopped()
                stopSelfResult(latestStartId)
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Rivet processes",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when a local project preview is running."
                setShowBadge(false)
            })
        }
    }

    private fun updateForegroundNotification(count: Int) {
        val notification = notification(count)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun notification(count: Int): Notification {
        val open = PendingIntent.getActivity(this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PROCESSES, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, REQUEST_STOP,
            Intent(this, PersistentProcessService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val countText = if (count == 1) "1 local preview is running" else "$count local previews are running"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_processes)
            .setContentTitle("Rivet")
            .setContentText(countText)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop all", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rivet_local_processes"
        private const val NOTIFICATION_ID = 110
        private const val REQUEST_OPEN = 111
        private const val REQUEST_STOP = 112
        private const val IDLE_STOP_GRACE_MS = 500L
        internal const val ACTION_START = "com.kaiser.rivet.action.START_PROCESSES"
        internal const val ACTION_STOP_ALL = "com.kaiser.rivet.action.STOP_ALL_PROCESSES"
    }
}
