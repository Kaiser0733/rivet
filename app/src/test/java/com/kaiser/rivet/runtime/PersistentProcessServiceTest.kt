package com.kaiser.rivet.runtime

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PersistentProcessServiceTest {
    private fun actions(notification: Notification): List<String> =
        notification.actions.orEmpty().map { it.title.toString() }

    private fun service(): PersistentProcessService =
        Robolectric.buildService(PersistentProcessService::class.java).create().get()

    @Test
    fun nonSensitiveApprovalGetsApproveAndDenyActionsInTheRunNotification() {
        val notification = service().notification(ForegroundServiceState(
            activeTurns = 1,
            runTitle = "Create a file",
            runAction = "create_file: Creating notes.txt",
            approvalTitle = "Create file",
            approvalToken = 7L,
            approvalDangerous = false,
        ))

        assertTrue(actions(notification).contains("Approve"))
        assertTrue(actions(notification).contains("Deny"))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            .contains("Approval needed"))
    }

    @Test
    fun sensitiveApprovalDoesNotOfferActionsAndSaysToOpenTheApp() {
        val notification = service().notification(ForegroundServiceState(
            activeTurns = 1,
            runTitle = "Delete a file",
            approvalTitle = "Delete file?",
            approvalToken = 8L,
            approvalDangerous = true,
        ))

        assertFalse(actions(notification).contains("Approve"))
        assertFalse(actions(notification).contains("Deny"))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            .contains("open the app"))
    }

    @Test
    fun activeRunNotificationUsesAChronometer() {
        val notification = service().notification(ForegroundServiceState(
            activeTurns = 1,
            runAction = "Working",
            runStartedAt = System.currentTimeMillis() - 5_000,
        ))

        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun noApprovalStateNeverAddsApprovalActions() {
        val notification = service().notification(ForegroundServiceState(
            activeTurns = 1,
            runAction = "Editing a file",
        ))

        assertFalse(actions(notification).contains("Approve"))
        assertFalse(actions(notification).contains("Deny"))
        assertEquals(listOf("Stop", "Pause"), actions(notification))
    }
}
