package com.kaiser.rivet.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kaiser.rivet.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps a model download alive with the existing dataSync foreground capability. */
class ModelDownloadService : Service() {
    companion object {
        const val ACTION_START = "com.kaiser.rivet.action.MODEL_DOWNLOAD_START"
        const val ACTION_STOP = "com.kaiser.rivet.action.MODEL_DOWNLOAD_STOP"
        const val ACTION_PAUSE = "com.kaiser.rivet.action.MODEL_DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "com.kaiser.rivet.action.MODEL_DOWNLOAD_CANCEL"
        const val EXTRA_ID = "model_id"
        private const val CHANNEL = "model_downloads"
        private const val NOTIFICATION_ID = 131
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for local model downloads"
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = LocalRuntime.downloads
        if (manager == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> {
                intent.getStringExtra(EXTRA_ID)?.let(manager::pause)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_ID)?.let(manager::cancel)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (manager.jobs.value.values.none {
                        it.status == DownloadStatus.Downloading ||
                            it.status == DownloadStatus.Verifying ||
                            it.status == DownloadStatus.Queued
                    }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> Unit
        }
        val active = manager.jobs.value.values.firstOrNull {
            it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Verifying ||
                it.status == DownloadStatus.Queued
        } ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(notification(active))
        if (observing == null) {
            observing = scope.launch {
                manager.jobs.collectLatest { jobs ->
                    val current = jobs.values.firstOrNull {
                        it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Verifying ||
                            it.status == DownloadStatus.Queued
                    } ?: run {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification(current))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(value: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, value)
        }
    }

    private fun notification(job: DownloadJob): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1,
            Intent(this, ModelDownloadService::class.java)
                .setAction(ACTION_PAUSE).putExtra(EXTRA_ID, job.entry.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 2,
            Intent(this, ModelDownloadService::class.java)
                .setAction(ACTION_CANCEL).putExtra(EXTRA_ID, job.entry.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = (job.fraction * 100).toInt()
        val text = when (job.status) {
            DownloadStatus.Downloading -> "$percent% · ${formatBytes(job.downloaded)} of ${formatBytes(job.total)}"
            DownloadStatus.Verifying -> "Checking the file…"
            else -> "Waiting to resume"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${job.entry.name}")
            .setContentText(text)
            .setProgress(100, percent, job.status == DownloadStatus.Verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        else -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)
    }

    override fun onTimeout(startId: Int) {
        LocalRuntime.downloads?.let { manager ->
            manager.jobs.value.values.firstOrNull { it.status == DownloadStatus.Downloading }
                ?.let { manager.pause(it.entry.id) }
        }
        stopSelf()
    }

    override fun onDestroy() {
        observing?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
