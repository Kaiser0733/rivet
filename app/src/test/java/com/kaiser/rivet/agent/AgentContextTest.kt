package com.kaiser.rivet.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextTest {
    @Test fun budgetPlanKeepsAContiguousRecentTailAndLatestUserTurns() {
        val history = buildList {
            repeat(10) { index ->
                add(AgentMessage.user(if (index == 0) "Fix login without changing auth policy" else "Inspect $index"))
                add(AgentMessage.assistant("", listOf(AgentToolCall("call-$index", "read_file", "{}"))))
                add(AgentMessage.tools(listOf(AgentToolResult("call-$index", "read_file",
                    "x".repeat(8_000), summary = "Read $index"))))
            }
        }

        val plan = AgentContext.plan(history, targetBytes = 35_000)!!

        assertTrue(plan.retainedBytes <= 35_000)
        assertTrue(plan.retained.any { it.role == AgentRole.User && it.text == "Inspect 8" })
        assertTrue(plan.retained.any { it.role == AgentRole.User && it.text == "Inspect 9" })
        assertFalse(plan.retained.any { it.text == "Fix login without changing auth policy" })
        assertTrue(plan.summaryInput.contains("Fix login without changing auth policy"))
        for (index in plan.retained.indices) {
            val call = plan.retained[index].toolCalls.firstOrNull() ?: continue
            assertEquals(call.id, plan.retained[index + 1].toolResults.single().callId)
        }
        assertEquals(30, history.size)
    }

    @Test fun oldSuccessfulToolBodyPrunesWithoutChangingHistoryOrCorrelatedErrors() {
        val oldCall = AgentToolCall("old", "read_file", "{\"path\":\"A.kt\"}")
        val failedCall = AgentToolCall("failed", "write_file", "{\"path\":\"B.kt\"}")
        val history = listOf(
            AgentMessage.user("Inspect A"),
            AgentMessage.assistant("", listOf(oldCall)),
            AgentMessage.tools(listOf(AgentToolResult("old", "read_file",
                "{\"content\":\"${"x".repeat(22_000)}\",\"sha256\":\"abc\"}", summary = "Read A.kt"))),
            AgentMessage.user("Fix B"),
            AgentMessage.assistant("", listOf(failedCall)),
            AgentMessage.tools(listOf(AgentToolResult("failed", "write_file",
                "{\"error\":\"conflict\"}", error = true, summary = "Conflict B.kt"))),
        )

        val pruned = AgentContext.pruneOldResults(history, protectedTailBytes = 1024)!!

        assertEquals("Fix B", pruned[3].text)
        assertTrue(pruned[2].toolResults.single().content.contains("\"output_pruned\":true"))
        assertTrue(pruned[2].toolResults.single().content.contains("\"sha256\":\"abc\""))
        assertEquals("old", pruned[2].toolResults.single().callId)
        assertEquals(history[5], pruned[5])
        assertTrue(history[2].toolResults.single().content.contains("x".repeat(22_000)))
        assertNull(AgentContext.pruneOldResults(pruned, protectedTailBytes = 1024))
    }

    @Test fun pressureRemovesOldBulkyResultsAndKeepsRecentPairs() {
        val history = mutableListOf<AgentMessage>()
        repeat(22) { index ->
            history += AgentMessage.user("Inspect part $index")
            history += AgentMessage.assistant("", listOf(AgentToolCall("call-$index", "read_file", "{}")))
            history += AgentMessage.tools(listOf(AgentToolResult("call-$index", "read_file",
                "x".repeat(22_000), summary = "Read part $index")))
        }
        val original = history.toList()

        val plan = AgentContext.plan(history, targetBytes = 160 * 1024)

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
        assertNull(AgentContext.plan(messages, targetBytes = 100 * 1024))
        assertNotNull(AgentContext.plan(messages, targetBytes = 35 * 1024))
        assertNull(AgentContext.plan(messages + AgentMessage.tools(listOf(
            AgentToolResult("missing", "read_file", "{}"))), targetBytes = 35 * 1024))
    }

    @Test fun projectionHandlesMultibyteTextWithinByteLimit() {
        val messages = buildList {
            repeat(100) { index ->
                add(AgentMessage.user("内容".repeat(1800) + index))
                add(AgentMessage.assistant("考察".repeat(1800)))
            }
        }
        val plan = AgentContext.plan(messages, targetBytes = 160 * 1024)
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
            AgentMessage.tools(listOf(AgentToolResult("shell", "run_command", "x".repeat(22_000),
                summary = "Command exited 0"))),
        )
        repeat(22) { index ->
            messages += AgentMessage.user("Inspect $index")
            messages += AgentMessage.assistant("", listOf(AgentToolCall("read-$index", "read_file", "{}")))
            messages += AgentMessage.tools(listOf(AgentToolResult("read-$index", "read_file", "x".repeat(22_000))))
        }
        val plan = AgentContext.plan(messages, targetBytes = 160 * 1024)!!
        assertTrue(plan.summaryInput.contains("run_command"))
        assertFalse(plan.summaryInput.contains("private-token-12345"))
    }

    @Test fun removedUserTextIsRedactedBeforeSummaryRequest() {
        val secret = "sk-abcdefghijklmnopqrstuvwxyz123456"
        val messages = listOf(
            AgentMessage.user("Use token $secret while investigating " + "x".repeat(8_000)),
            AgentMessage.assistant("Investigation complete"),
            AgentMessage.user("Continue with the fix"),
        )

        val plan = AgentContext.plan(messages, targetBytes = 2_000)!!

        assertFalse(plan.summaryInput.contains(secret))
        assertTrue(plan.summaryInput.contains("[redacted]"))
        assertEquals("Continue with the fix", plan.retained.last().text)
    }

    @Test fun budgetedSummaryKeepsNewestWholeRemovedGroupAndMarksOlderOmission() {
        val call = AgentToolCall("boundary", "inspect_boundary", """{"path":"src/Boundary.kt"}""")
        val removed = listOf(
            AgentMessage.user("ancient objective"),
            AgentMessage.assistant("ancient answer"),
            AgentMessage.user("boundary-adjacent request"),
            AgentMessage.assistant("", listOf(call)),
            AgentMessage.tools(listOf(AgentToolResult(
                "boundary", "inspect_boundary", "{}", summary = "Boundary result",
            ))),
        )

        val summary = AgentContext.summarizeInput(removed, maxBytes = 192)

        assertTrue(summary.toByteArray(Charsets.UTF_8).size <= 192)
        assertTrue(summary.contains("Earlier removed events omitted"))
        assertFalse(summary.contains("ancient objective"))
        assertTrue(summary.contains("TOOL CALL: inspect_boundary"))
        assertTrue(summary.contains("TOOL RESULT: inspect_boundary"))
        assertTrue(summary.indexOf("TOOL CALL: inspect_boundary") <
            summary.indexOf("TOOL RESULT: inspect_boundary"))
    }

    @Test fun summaryRequiresTruthfulMarkerWhenNonemptyInputCannotFit() {
        val error = try {
            AgentContext.summarizeInput(listOf(AgentMessage.user("removed event")), maxBytes = 1)
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertNotNull(error)
        assertEquals("summary_input_limit", error!!.message)
    }

    @Test fun summaryOmissionNeverKeepsOnlyHalfOfNewestToolGroup() {
        val call = AgentToolCall("boundary", "inspect_boundary", "{}")
        val removed = listOf(
            AgentMessage.user("older event"),
            AgentMessage.assistant("", listOf(call)),
            AgentMessage.tools(listOf(AgentToolResult("boundary", "inspect_boundary", "{}"))),
        )

        val summary = AgentContext.summarizeInput(removed, maxBytes = 16)

        assertTrue(summary.contains("older omitted"))
        assertFalse(summary.contains("TOOL CALL: inspect_boundary"))
        assertFalse(summary.contains("TOOL RESULT: inspect_boundary"))
        assertTrue(summary.toByteArray(Charsets.UTF_8).size <= 16)
    }

    @Test fun runCommandWithUnhealthyOrUnknownStatusIsNotPruned() {
        val cases = listOf(
            "nonzero exit" to commandContent(exitCode = 7),
            "timeout" to commandContent(timedOut = true),
            "sync conflict" to commandContent(sync = "conflict", syncPath = "src/A.kt"),
            "sync failed" to commandContent(sync = "failed"),
            "sync pending" to commandContent(sync = "pending"),
            "malformed status" to commandContent().replace("\"exit_code\":0", "\"exit_code\":\"0\""),
            "missing status" to commandContent(exitCode = null),
            "malformed JSON" to "x".repeat(9_000),
        )

        for ((label, content) in cases) {
            val history = commandHistory(content)
            assertNull(label, AgentContext.pruneOldResults(history, protectedTailBytes = 0))
        }
    }

    @Test fun healthyRunCommandCanPruneWhileRetainingAuthoritativeStatus() {
        val history = commandHistory(commandContent(outputSize = 9_000))

        val pruned = AgentContext.pruneOldResults(history, protectedTailBytes = 0)!!
        val content = pruned[2].toolResults.single().content

        assertTrue(content.contains("\"output_pruned\":true"))
        assertTrue(content.contains("\"exit_code\":0"))
        assertTrue(content.contains("\"sync\":\"ok\""))
        assertTrue(content.contains("\"timed_out\":false"))
        assertTrue(content.contains("\"stdout_truncated\":false"))
        assertTrue(content.contains("\"stderr_truncated\":false"))
        assertTrue(AgentContext.summarizeInput(history.take(3), maxBytes = 256)
            .contains("run_command ok"))
    }

    @Test fun healthyRunCommandWithTruncatedOutputPrunesAndRetainsTruncationFacts() {
        val history = commandHistory(commandContent(stdoutTruncated = true, stderrTruncated = true))

        val pruned = AgentContext.pruneOldResults(history, protectedTailBytes = 0)!!
        val content = pruned[2].toolResults.single().content
        val summary = AgentContext.summarizeInput(history.take(3), maxBytes = 256)

        assertTrue(content.contains("\"stdout_truncated\":true"))
        assertTrue(content.contains("\"stderr_truncated\":true"))
        assertTrue(summary.contains("run_command ok"))
        assertTrue(summary.contains("stdout_truncated=true"))
        assertTrue(summary.contains("stderr_truncated=true"))
    }

    @Test fun commandSummaryDoesNotCallAConflictSuccessful() {
        val history = commandHistory(commandContent(sync = "conflict", syncPath = "src/A.kt"))

        val summary = AgentContext.summarizeInput(history.take(3), maxBytes = 512)

        assertTrue(summary.contains("run_command unresolved"))
        assertTrue(summary.contains("sync=conflict"))
        assertFalse(summary.contains("run_command ok"))
    }

    @Test fun minimumProjectionKeepsLatestUserAndNewestCompleteToolGroup() {
        val latestCall = AgentToolCall("latest", "read_file", "{}")
        val history = listOf(
            AgentMessage.user("old request"),
            AgentMessage.assistant("old answer"),
            AgentMessage.user("latest objective"),
            AgentMessage.assistant("latest reasoning"),
            AgentMessage.assistant("", listOf(latestCall)),
            AgentMessage.tools(listOf(AgentToolResult("latest", "read_file", "{}"))),
        )

        val minimum = AgentContext.minimumProjection(history)!!

        assertTrue(AgentContext.validGroups(minimum))
        assertFalse(minimum.any { it.text == "old request" })
        assertTrue(minimum.any { it.text == "latest objective" })
        assertEquals("latest", minimum.last().toolResults.single().callId)
    }

    @Test fun minimumProjectionWithoutUserKeepsNewestCompleteGroup() {
        val call = AgentToolCall("latest", "read_file", "{}")
        val history = listOf(
            AgentMessage.assistant("older response"),
            AgentMessage.assistant("", listOf(call)),
            AgentMessage.tools(listOf(AgentToolResult("latest", "read_file", "{}"))),
        )

        val minimum = AgentContext.minimumProjection(history)!!

        assertEquals(2, minimum.size)
        assertEquals("latest", minimum.last().toolResults.single().callId)
    }

    @Test fun minimumSavingsOverrideAllowsSmallVerifiedReduction() {
        val history = listOf(
            AgentMessage.user("old " + "x".repeat(2_300)),
            AgentMessage.assistant("old response"),
            AgentMessage.user("current"),
            AgentMessage.assistant("done"),
        )

        assertNull(AgentContext.plan(history, targetBytes = 1_000))
        assertNotNull(AgentContext.plan(history, targetBytes = 1_000, minimumSavingsBytes = 1))
    }

    private fun commandHistory(content: String) = listOf(
        AgentMessage.user("Run the project command"),
        AgentMessage.assistant("", listOf(AgentToolCall("command", "run_command", "{}"))),
        AgentMessage.tools(listOf(AgentToolResult(
            "command", "run_command", content, summary = "Command exited 0",
        ))),
        AgentMessage.user("Continue"),
        AgentMessage.assistant("Done"),
    )

    private fun commandContent(
        exitCode: Int? = 0,
        timedOut: Boolean = false,
        sync: String = "ok",
        stdoutTruncated: Boolean = false,
        stderrTruncated: Boolean = false,
        syncPath: String? = null,
        outputSize: Int = 9_000,
    ): String = buildJsonObject {
        exitCode?.let { put("exit_code", it) }
        put("stdout", "x".repeat(outputSize))
        put("stderr", "")
        put("stdout_truncated", stdoutTruncated)
        put("stderr_truncated", stderrTruncated)
        put("timed_out", timedOut)
        put("sync", sync)
        syncPath?.let { put("sync_path", it) }
    }.toString()
}
