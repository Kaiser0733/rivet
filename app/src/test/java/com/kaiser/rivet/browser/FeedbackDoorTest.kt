package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDoorTest {
    @Test fun settingsAndChatShareTheFeedbackDoor() {
        fun source(path: String): String = listOf(File("src/main/java/$path"),
            File("app/src/main/java/$path")).first { it.isFile }.readText()
        val app = source("com/kaiser/rivet/ui/RivetApp.kt")
        val chat = source("com/kaiser/rivet/ui/chat/ChatScreen.kt")
        val settings = source("com/kaiser/rivet/ui/provider/SettingsScreen.kt")
        assertTrue(app.contains("https://vastraalabs.online/feedback"))
        assertTrue(app.contains("onSendFeedback = sendFeedback"))
        assertTrue(chat.contains("Send feedback"))
        assertTrue(settings.contains("Send feedback"))
    }
}
