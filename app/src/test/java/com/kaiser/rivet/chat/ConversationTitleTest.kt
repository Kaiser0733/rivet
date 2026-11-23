package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.provider.ReasoningLevel
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleTest {
    @Test fun aFailureReportIsNotAnAutoTitle() {
        assertNull(ConversationTitle.sanitize("Termux echo command failed"))
        assertNull(ConversationTitle.sanitize("Error: Termux timed out"))
        assertEquals("Investigate Build Failure", ConversationTitle.sanitize("Investigate Build Failure"))
    }

    @Test fun anUnsuccessfulTurnDoesNotSupplyAnAnswerForAutoTitle() {
        val user = AgentMessage.user("Run echo through Termux")
        assertNull(ConversationTitle.completedAnswer(listOf(user,
            AgentMessage.assistant("The Termux echo command failed."))))
        val failure = AgentMessage.tools(listOf(
            AgentToolResult("echo", "termux_command", """{"exit_code":1,"stderr":"permission denied"}"""),
        ))
        assertNull(ConversationTitle.completedAnswer(listOf(user, failure, AgentMessage.assistant("See the output."))))
        val success = AgentMessage.tools(listOf(
            AgentToolResult("retry", "termux_command", """{"exit_code":0,"stdout":"hello"}"""),
        ))
        assertEquals("Echo printed hello.", ConversationTitle.completedAnswer(listOf(user, failure, success,
            AgentMessage.assistant("Echo printed hello."))))
    }

    @Test fun requestContainsOnlyBoundedVisibleTextAndDefaultReasoning() {
        val request = ConversationTitle.request("model", "😀".repeat(2000), "é".repeat(2000))
        assertEquals(ReasoningLevel.Default, request.reasoning)
        assertTrue(request.tools.isEmpty())
        assertEquals(listOf(AgentRole.User), request.messages.map { it.role })
        val text = request.messages.single().text
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 3072 + 64)
        assertEquals("First user message:\n" + "😀".repeat(512) +
            "\n\nCompleted assistant answer:\n" + "é".repeat(512), text)
        assertTrue(request.messages.all { it.internalContext == null && it.transportState == null &&
            it.toolCalls.isEmpty() && it.toolResults.isEmpty() })
        assertFalse(request.system.contains("AGENTS"))
        assertFalse(request.system.contains("TaskState"))
    }

    @Test fun sanitizeUsesFirstNonemptyLineAndRemovesDecorations() {
        assertEquals("Fix Login Crash", ConversationTitle.sanitize("\n # \"Fix Login Crash.\"\nextra text"))
        assertEquals("Fix Login Crash", ConversationTitle.sanitize("- `Fix Login Crash!`"))
        assertEquals("Fix Login Crash", ConversationTitle.sanitize("1. Fix Login Crash"))
        assertEquals("Fix Login Crash", ConversationTitle.sanitize("\"# Fix Login Crash\""))
        assertEquals("Fix Login Crash", ConversationTitle.sanitize("<think>\nThinking about title...\n</think>\nTitle: **Fix Login Crash**"))
        assertEquals("These Seven Useful Words Still Make A Title",
            ConversationTitle.sanitize("These Seven Useful Words Still Make A Title"))
    }

    @Test fun invalidOrStructuredOutputIsIgnoredAndUnicodeBoundsHold() {
        listOf("", "   ", "{}", "[\"Title\"]", "```json\n{}", "tool_call: {}", "<tool>name</tool>", "\"title\": \"Bad\"", "arguments: bad")
            .forEach { assertNull(it, ConversationTitle.sanitize(it)) }
        assertEquals(60, ConversationTitle.sanitize("a".repeat(120))!!.length)
        val title = ConversationTitle.sanitize("😀".repeat(120))!!
        assertEquals(80, title.toByteArray(Charsets.UTF_8).size)
        assertEquals("😀".repeat(20), title)
        assertEquals("Fix crash", ConversationTitle.sanitize("Fix \uD800crash"))
    }
}
