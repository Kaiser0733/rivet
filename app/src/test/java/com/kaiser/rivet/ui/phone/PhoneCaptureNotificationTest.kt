package com.kaiser.rivet.ui.phone

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.kaiser.rivet.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneCaptureNotificationTest {
    @Test fun bodyOpensRivetAndOnlyTheCaptureButtonSendsACaptureCommand() {
        val service = Robolectric.buildService(PhoneCaptureService::class.java).create().get()
        val build = PhoneCaptureService::class.java.getDeclaredMethod("notification", String::class.java)
        build.isAccessible = true
        val notification = build.invoke(service, "capture-test") as Notification
        val capture = notification.actions.first { it.title == "Capture now" }

        val body = shadowOf(notification.contentIntent)
        assertTrue(body.isActivity)
        assertEquals(MainActivity::class.java.name, body.savedIntent.component?.className)
        assertEquals(Intent.ACTION_MAIN, body.savedIntent.action)
        assertNotEquals(capture.actionIntent, notification.contentIntent)
        assertTrue(shadowOf(capture.actionIntent).isService)

        val manager = service.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(notification.channelId).importance)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }
}
