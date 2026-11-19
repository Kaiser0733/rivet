package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.local.LocalPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePromptTest {
    @Test fun cloudSystemPromptExplainsPhoneAccessAndTheAddOnOnce() {
        for (workspace in listOf(true, false)) {
            val prompt = ChatViewModel.systemInstruction(workspace)
            assertTrue(prompt.contains("\n\nPhone access"))
            assertTrue(prompt.contains("Settings → Phone access → Advanced"))
            assertTrue(prompt.contains("say once"))
        }
    }

    @Test fun localPromptRequiresACloudModelEvenInCompactMode() {
        for (window in listOf(4096, 8192)) {
            val local = LocalPrompt.wire("Base", emptyList(), window, workspace = true)
            assertTrue(local.system.contains("Phone tools need a cloud model."))
        }
    }

    @Test fun phoneFailuresAndActivityHavePlainExplanations() {
        val error = AgentToolError.content("capability_addon_required")
        assertTrue(error.contains("Settings → Phone access → Advanced"))
        assertEquals("Opening an app…", ChatViewModel.activityFor("open_app"))
        assertEquals("Sharing…", ChatViewModel.activityFor("share_out"))
    }
}
