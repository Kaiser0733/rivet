package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextTest {
    @Test fun pressureRemovesOldBulkyResultsAndKeepsRecentPairs() {
        val history = mutableListOf<AgentMessage>()
        repeat(22) { index ->
            history += AgentMessage.user("Inspect part $index")
            history += AgentMessage.assistant("", listOf(AgentToolCall("call-$index", "read_file", "{}")))
            history += AgentMessage.tools(listOf(AgentToolResult("call-$index", "read_file",
                "x".repeat(22_000), summary = "Read part $index")))
        }
        val original = history.toList()

        val plan = AgentContext.plan(history)

        assertNotNull(plan)
        assertTrue(plan!!.retainedBytes < plan.originalBytes)
        assertTrue(plan.summaryInput.toByteArray().size <= 96 * 1024)
        assertFalse(plan.summaryInput.contains("x".repeat(100)))
        assertEquals(original, history)
        for (index in plan.retained.indices) {
            val message = plan.retained[index]
            if (message.toolCalls.isNotEmpty()) {
                assertEquals(message.toolCalls.map { it.id },
                    plan.retained[index + 1].toolResults.map { it.callId })
            }
        }
    }

    @Test fun forcedCompressionWorksBelowStoragePressureAndOrphansRefuse() {
        val messages = listOf(
            AgentMessage.user("old task " + "a".repeat(30_000)),
            AgentMessage.assistant("old answer " + "b".repeat(30_000)),
            AgentMessage.user("current task"),
            AgentMessage.assistant("response"),
        )
        assertNull(AgentContext.plan(messages))
        assertNotNull(AgentContext.plan(messages, force = true))
        assertNull(AgentContext.plan(messages + AgentMessage.tools(listOf(
            AgentToolResult("missing", "read_file", "{}"))), force = true))
    }

    @Test fun projectionHandlesMultibyteTextWithinByteLimit() {
        val messages = buildList {
            repeat(100) { index ->
                add(AgentMessage.user("内容".repeat(1800) + index))
                add(AgentMessage.assistant("考察".repeat(1800)))
            }
        }
        val plan = AgentContext.plan(messages)
        assertNotNull(plan)
        val encoded = plan!!.summaryInput.toByteArray(Charsets.UTF_8)
        assertTrue(encoded.size <= 96 * 1024)
        assertEquals(plan.summaryInput, encoded.toString(Charsets.UTF_8))
    }

    @Test fun commandArgumentsAreNotCopiedIntoCompactionPrompt() {
        val command = AgentToolCall("shell", "run_command", """{"command":"echo private-token-12345"}""")
        val messages = mutableListOf(
            AgentMessage.user("Run a test"),
            AgentMessage.assistant("", listOf(command)),
            AgentMessage.tools(listOf(AgentToolResult("shell", "run_command", "{}", summary = "Command exited 0"))),
        )
        repeat(22) { index ->
            messages += AgentMessage.user("Inspect $index")
            messages += AgentMessage.assistant("", listOf(AgentToolCall("read-$index", "read_file", "{}")))
            messages += AgentMessage.tools(listOf(AgentToolResult("read-$index", "read_file", "x".repeat(22_000))))
        }
        val plan = AgentContext.plan(messages)!!
        assertTrue(plan.summaryInput.contains("run_command"))
        assertFalse(plan.summaryInput.contains("private-token-12345"))
    }
}
