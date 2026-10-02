package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.provider.ReasoningLevel
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleTest {
    @Test fun requestContainsOnlyBoundedVisibleTextAndDefaultReasoning() {
        val request = ConversationTitle.request("model", "😀".repeat(2000), "é".repeat(2000))
        assertEquals(ReasoningLevel.Default, request.reasoning)
        assertTrue(request.tools.isEmpty())
        assertEquals(listOf(AgentRole.User, AgentRole.Assistant), request.messages.map { it.role })
        assertTrue(request.messages[0].text.toByteArray(Charsets.UTF_8).size <= 2048)
        assertTrue(request.messages[1].text.toByteArray(Charsets.UTF_8).size <= 1024)
        assertEquals("😀".repeat(512), request.messages[0].text)
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
