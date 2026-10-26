package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentActivityProjection
import com.kaiser.rivet.agent.AgentConversationItem
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationTest {
    @Test fun conversationProjectsActivityWithoutRawToolState() {
        val user = AgentMessage.user("Fix the login crash")
        val call = AgentToolCall("private-id", "read_file", "{\"path\":\"src/login.kt\"}")
        val messages = listOf(user,
            AgentMessage.assistant("I'll inspect files", listOf(call)),
            AgentMessage.tools(listOf(AgentToolResult(call.id, call.name,
                "{\"sha256\":\"secret\"}", summary = "Read src/login.kt"))),
            AgentMessage.assistant("I fixed the login crash."))

        val timeline = AgentActivityProjection.conversation(messages)
        val visible = timeline.filterIsInstance<AgentConversationItem.Message>().map { it.message }

        assertEquals(listOf(user, AgentMessage.assistant("I'll inspect files"), messages.last()), visible)
        assertFalse(visible.any { it.toolResults.isNotEmpty() || it.toolCalls.isNotEmpty() })
        assertFalse(timeline.toString().contains("private-id"))
        assertFalse(timeline.toString().contains("secret"))
    }

    @Test fun approvalTitleMakesCommandAndDestructionUnderstandable() {
        val command = AgentApprovalRequest(AgentToolCall("id", "run_command", "{}"),
            "Run command", "./gradlew test", dangerous = true)
        val deletion = AgentApprovalRequest(AgentToolCall("id", "delete_path", "{}"),
            "Delete existing file?", "old-config.json", dangerous = true)
        assertEquals("Run a project command?", approvalTitle(command))
        assertEquals("Delete existing file?", approvalTitle(deletion))
        assertTrue(COMMAND_APPROVAL_WARNING.contains("Rivet's saved data"))
        assertTrue(COMMAND_APPROVAL_WARNING.contains("read, change, or delete project files"))
        assertTrue(COMMAND_APPROVAL_WARNING.contains("network"))
    }

    @Test fun bidiFormattingControlsAreVisibleInApprovalAndPathText() {
        assertEquals("safe\\u202Etxt", displaySafeText("safe\u202Etxt"))
    }

    @Test fun claudeModelIdsBecomeReadableWords() {
        assertEquals("Sonnet 4 5", displayModelName("anthropic/claude-sonnet-4-5"))
        assertEquals("Opus 4 8", displayModelName("claude-opus-4-8"))
    }

    @Test fun gptModelIdsKeepTheirShapeWithUpperGPTPrefix() {
        assertEquals("GPT-5.2-codex", displayModelName("openai/gpt-5.2-codex"))
        assertEquals("GPT-4o", displayModelName("gpt-4o"))
    }

    @Test fun otherModelIdsAreLeftAlone() {
        assertEquals("DeepSeek-V4.1-Flash-1017",
            displayModelName("deepseek-ai/DeepSeek-V4.1-Flash-1017"))
        assertEquals("llama-3.3-70b", displayModelName("llama-3.3-70b"))
        assertEquals("model", displayModelName("model"))
        assertEquals("", displayModelName(""))
    }
}
