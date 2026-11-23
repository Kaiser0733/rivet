package com.kaiser.rivet.ui.phone

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneCaptureNotificationTest {
    @Test fun tappingCollapsedNotificationUsesTheCaptureAction() {
        val service = Robolectric.buildService(PhoneCaptureService::class.java).create().get()
        val build = PhoneCaptureService::class.java.getDeclaredMethod("notification", String::class.java)
        build.isAccessible = true
        val notification = build.invoke(service, "capture-test") as Notification
        val capture = notification.actions.first { it.title == "Capture now" }

        assertNotNull(notification.contentIntent)
        assertEquals(capture.actionIntent, notification.contentIntent)
    }
}
