package com.kaiser.rivet.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerResultPruneTest {
    @Test fun deliveredWorkerResultSurvivesCoordinatorPruningIntact() {
        val envelope = buildJsonObject {
            put("worker", "worker-1")
            put("state", "done")
            put("summary", "Checked every call site")
            put("payload", "w".repeat(2_700))
        }.toString()
        val history = listOf(
            AgentMessage.user("Split this task"),
            AgentMessage.assistant("", listOf(AgentToolCall("read-1", "read_file", "{}"))),
            AgentMessage.tools(listOf(AgentToolResult(
                "read-1", "read_file", "r".repeat(9_000), summary = "Read a big file",
            ))),
            AgentMessage.assistant("", listOf(AgentToolCall("await-1", "await_subagents", "{}"))),
            AgentMessage.tools(listOf(AgentToolResult(
                "await-1", "await_subagents", envelope, summary = "Worker done",
            ))),
            AgentMessage.user("Continue"),
            AgentMessage.assistant("Done"),
        )

        val pruned = requireNotNull(
            AgentContext.pruneOldResults(history, protectedTailBytes = 0, minimumSavingsBytes = 1)) {
            "the older read_file group should still shrink"
        }
        assertTrue(pruned[2].toolResults.single().content.contains("\"output_pruned\":true"))
        val kept = pruned[4].toolResults.single().content
        assertEquals(envelope, kept)
        assertFalse(kept.contains("output_pruned"))
        assertTrue(kept.contains("w".repeat(2_700)))
    }
}
