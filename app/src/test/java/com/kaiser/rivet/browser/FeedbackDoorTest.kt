package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDoorTest {
    @Test fun settingsAndChatShareTheFeedbackDoor() {
        val app = File("app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt").readText()
        val chat = File("app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt").readText()
        val settings = File("app/src/main/java/com/kaiser/rivet/ui/provider/SettingsScreen.kt").readText()
        assertTrue(app.contains("https://vastraalabs.online/feedback"))
        assertTrue(app.contains("onSendFeedback = sendFeedback"))
        assertTrue(chat.contains("Send feedback"))
        assertTrue(settings.contains("Send feedback"))
    }
}
