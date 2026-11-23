package com.kaiser.rivet.ui.phone

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.kaiser.rivet.RivetApplication
import com.kaiser.rivet.runtime.PhoneCaptureResult
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhoneCaptureService : Service() {
    private val coordinator get() = (application as RivetApplication).phoneCapture
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureId: String? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val captureDeadline = Runnable {
        finish(PhoneCaptureResult(error = "phone_shot_timeout"))
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = finish(PhoneCaptureResult(error = "phone_shot_cancelled"))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.data?.lastPathSegment
        when {
            intent?.action == ARM && id != null && captureId == null && coordinator.canArm(id) -> arm(id, intent)
            intent?.action == CAPTURE && id != null && id == captureId && coordinator.beginFrame(id) -> {
                main.removeCallbacks(captureDeadline)
                // The notification action can leave the shade open. Give the user time to close it.
                main.postDelayed({ capture() }, 1_500)
            }
            intent?.action == CANCEL && id != null && id == captureId -> finish(PhoneCaptureResult(error = "phone_shot_cancelled"))
            captureId == null -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun arm(id: String, intent: Intent) {
        captureId = id
        try {
            val notification = notification(id)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTIFICATION_ID, notification)

            @Suppress("DEPRECATION")
            val consent = requireNotNull(intent.getParcelableExtra<Intent>(CONSENT))
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = requireNotNull(manager.getMediaProjection(Activity.RESULT_OK, consent)).also {
                it.registerCallback(projectionCallback, main)
            }
            val notifications = getSystemService(NotificationManager::class.java)
            if (!notifications.areNotificationsEnabled() ||
                notifications.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
                finish(PhoneCaptureResult(error = "phone_shot_notifications_off"))
                return
            }
            notifications.notify(NOTIFICATION_ID, notification)
            main.postDelayed(captureDeadline, ACTION_TIMEOUT_MS)
            scope.launch {
                repeat(20) {
                    if (captureId != id) return@launch
                    if (notifications.activeNotifications.any { it.id == NOTIFICATION_ID }) {
                        coordinator.notificationPosted(id)
                        return@launch
                    }
                    delay(100)
                }
                finish(PhoneCaptureResult(error = "phone_shot_notification_missing"))
            }
        } catch (_: Exception) {
            finish(PhoneCaptureResult(error = "phone_shot_unavailable"))
        }
    }

    private fun notification(id: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_DEFAULT))
        fun action(name: String): PendingIntent = PendingIntent.getService(
            this, 0, intent(this, name, id), PendingIntent.FLAG_IMMUTABLE,
        )
        val capture = action(CAPTURE)
        val cancel = action(CANCEL)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Rivet is ready to capture")
            .setStyle(Notification.BigTextStyle().bigText("Switch apps, tap this notification, then close the notification shade."))
            .setContentText("Switch apps, then tap this notification.")
            .setContentIntent(capture)
            .setOngoing(true)
            .setForegroundServiceBehaviorCompat()
            .setDeleteIntent(cancel)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_camera, "Capture now", capture).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build())
            .build()
    }

    private fun Notification.Builder.setForegroundServiceBehaviorCompat(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) else this

    @Suppress("DEPRECATION")
    private fun capture() {
        val current = projection ?: return
        try {
            val metrics = DisplayMetrics()
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            val frames = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = frames
            frames.setOnImageAvailableListener({ saveFirstFrame(it, width, height) }, main)
            display = requireNotNull(current.createVirtualDisplay("Rivet capture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, frames.surface, null, main))
            main.postDelayed({ finish(PhoneCaptureResult(error = "phone_shot_unavailable")) }, 5_000)
        } catch (_: Exception) {
            finish(PhoneCaptureResult(error = "phone_shot_unavailable"))
        }
    }

    private fun saveFirstFrame(frames: ImageReader, width: Int, height: Int) {
        val id = captureId ?: return
        val image = try { frames.acquireLatestImage() } catch (_: IllegalStateException) { return } ?: return
        val rivetVisible = coordinator.rivetVisible
        val bitmap = try {
            image.use {
                val plane = image.planes.first()
                require(plane.pixelStride == 4)
                val stride = plane.rowStride
                val rowBytes = width * plane.pixelStride
                val source = plane.buffer
                val packed = if (stride == rowBytes) source else ByteBuffer.allocateDirect(rowBytes * height).apply {
                    val row = source.duplicate()
                    for (index in 0 until height) {
                        row.limit(index * stride + rowBytes)
                        row.position(index * stride)
                        put(row)
                    }
                    rewind()
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame ->
                    try { frame.copyPixelsFromBuffer(packed) }
                    catch (e: Exception) { frame.recycle(); throw e }
                }
            }
        } catch (_: Exception) { return }
        releaseProjection()
        scope.launch {
            val target = File(File(cacheDir, "phone-shots"), "$id.png")
            var saved = false
            try {
                withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG encoding failed")
                    }
                }
                finish(PhoneCaptureResult(path = target.absolutePath, width = width, height = height,
                    rivetVisible = rivetVisible))
                saved = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { finish(PhoneCaptureResult(error = "phone_shot_unavailable")) }
            finally {
                if (!saved) target.delete()
                bitmap.recycle()
            }
        }
    }

    private fun releaseProjection() {
        main.removeCallbacksAndMessages(null)
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
    }

    private fun finish(result: PhoneCaptureResult) {
        val id = captureId ?: return
        captureId = null
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        coordinator.finish(id, result)
    }

    override fun onDestroy() {
        finish(PhoneCaptureResult(error = "phone_shot_cancelled"))
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "rivet_phone_capture"
        private const val NOTIFICATION_ID = 648
        internal const val ACTION_TIMEOUT_MS = 120_000L
        const val ARM = "com.kaiser.rivet.phone.ARM"
        const val CONSENT = "consent"
        private const val CAPTURE = "com.kaiser.rivet.phone.CAPTURE"
        private const val CANCEL = "com.kaiser.rivet.phone.CANCEL"

        fun intent(context: Context, action: String, id: String) = Intent(context, PhoneCaptureService::class.java)
            .setAction(action).setData(Uri.parse("rivet-capture://request/$id"))
    }
}
