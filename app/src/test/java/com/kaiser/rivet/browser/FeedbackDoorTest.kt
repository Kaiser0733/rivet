package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDoorTest {
    @Test fun settingsAndTheSlashPanelShareTheOneFeedbackDoor() {
        fun source(path: String): String = listOf(File("src/main/java/$path"),
            File("app/src/main/java/$path")).first { it.isFile }.readText()
        val app = source("com/kaiser/rivet/ui/RivetApp.kt")
        val chat = source("com/kaiser/rivet/ui/chat/ChatScreen.kt")
        val slash = source("com/kaiser/rivet/ui/chat/SlashMenu.kt")
        val settings = source("com/kaiser/rivet/ui/provider/SettingsScreen.kt")

        // One door, one URL: RivetApp builds sendFeedback once and passes that same
        // lambda to Settings and to the chat's slash panel.
        assertEquals(1, Regex(Regex.escape("https://vastraalabs.online/feedback")).findAll(app).count())
        assertEquals(2, Regex("onSendFeedback = sendFeedback").findAll(app).count())
        assertTrue(app.contains("onSendFeedback = sendFeedback"))

        assertTrue(settings.contains("\"Send feedback\""))
        assertTrue(settings.contains("onSendFeedback"))

        // The chat's own menu entry was removed (brief §1); the slash Feedback row is
        // the chat-side way in, and it calls the same callback the door is built from.
        assertTrue(slash.contains("\"Feedback\""))
        assertTrue(slash.contains("SlashAction.Feedback"))
        assertTrue(chat.contains("SlashAction.Feedback -> onSendFeedback()"))
    }
}
