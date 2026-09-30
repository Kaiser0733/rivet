package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityProjectionTest {
    @Test fun liveLifecycleCorrelationStaysFixedSizeForLargeProviderIds() {
        val providerId = "opaque-" + "x".repeat(100_000)
        val key = AgentToolLifecycle.keyFor(providerId)
        assertEquals(64, key.length)
        assertFalse(key.contains(providerId))
        val groupKey = AgentToolLifecycle.groupKeyFor(sequenceOf(providerId, "tool-id-2"))
        assertEquals(64, groupKey.length)
        assertFalse(groupKey.contains(providerId))
    }

    @Test fun groupsCorrelatedReadsEditsCreatesAndCommands() {
        val calls = listOf(
            AgentToolCall("r1", "read_file", """{"path":"src/Main.kt"}"""),
            AgentToolCall("r2", "read_file", """{"path":"src/Auth.kt"}"""),
            AgentToolCall("e1", "write_file", """{"path":"src/Main.kt","expected_sha256":"secret-hash"}"""),
            AgentToolCall("c1", "run_command", """{"command":"./gradlew test"}"""),
        )
        val results = listOf(
            AgentToolResult("r1", "read_file", """{"path":"src/Main.kt","sha256":"private-hash","text":"private source"}"""),
            AgentToolResult("r2", "read_file", """{"path":"src/Auth.kt","sha256":"private-hash"}"""),
            AgentToolResult("e1", "write_file", """{"path":"src/Main.kt","sha256":"private-hash"}"""),
            AgentToolResult("c1", "run_command", """{"exit_code":0,"stdout":"ok","sync":"ok"}"""),
        )
        val items = AgentActivityProjection.conversation(listOf(
            AgentMessage.user("Fix and test"), AgentMessage.assistant("I will inspect and edit.", calls,
                transportState = "private provider reasoning"),
            AgentMessage.tools(results),
        ))
        assertEquals(3, items.size)
        assertTrue(items[1] is AgentConversationItem.Message)
        assertEquals(null, (items[1] as AgentConversationItem.Message).message.transportState)
        val group = (items[2] as AgentConversationItem.Activity).group
        assertEquals("Read 2 files · Edited 1 file · Ran 1 command", group.summary)
        assertEquals(listOf(ActivityOutcome.Completed, ActivityOutcome.Completed,
            ActivityOutcome.Completed, ActivityOutcome.Completed), group.operations.map { it.outcome })
        val rendered = group.operations.joinToString(" ") { "${it.title} ${it.detail} ${it.outcomeDetail.orEmpty()}" }
        assertFalse(rendered.contains("private-hash"))
        assertFalse(rendered.contains("secret-hash"))
        assertFalse(rendered.contains("private source"))
        assertFalse(group.id.contains("r1"))
    }

    @Test fun distinguishesCommandFailureDenialBlockingAndUnresolvedCalls() {
        val calls = listOf(
            AgentToolCall("run", "run_command", """{"command":"false"}"""),
            AgentToolCall("deny", "delete_path", """{"path":"old.txt"}"""),
            AgentToolCall("block", "run_command", """{"command":"pwd"}"""),
            AgentToolCall("unknown", "read_file", """{"path":"unfinished.txt"}"""),
        )
        val results = listOf(
            AgentToolResult("run", "run_command", """{"exit_code":7,"sync":"ok"}"""),
            AgentToolResult("deny", "delete_path", """{"error":"denied"}""", error = true),
            AgentToolResult("block", "run_command", """{"error":"workspace_unavailable"}""", error = true),
        )
        val callsMessage = AgentMessage.assistant("", calls)
        val items = AgentActivityProjection.conversation(listOf(callsMessage, AgentMessage.tools(results)))
        val group = (items.single() as AgentConversationItem.Activity).group
        assertEquals(ActivityOutcome.Failed, group.operations[0].outcome)
        assertEquals("Exit 7", group.operations[0].outcomeDetail)
        assertEquals(ActivityOutcome.Denied, group.operations[1].outcome)
        assertEquals(ActivityOutcome.Blocked, group.operations[2].outcome)
        assertEquals(ActivityOutcome.Unknown, group.operations[3].outcome)
        assertEquals("Outcome unknown", group.operations[3].outcomeDetail)
        assertFalse(group.summary.contains("Deleted"))
        assertFalse(group.summary.contains("Ran"))
        assertTrue(group.summary.contains("Denied"))
        assertTrue(group.summary.contains("Failed"))
    }

    @Test fun projectsDownloadsAndPreviewsWithoutUrlsTokensOrProviderSyntax() {
        val calls = listOf(
            AgentToolCall("download", "download_file",
                """{"url":"https://example.test/file?token=ultra-secret","path":"assets/icon.svg"}"""),
            AgentToolCall("preview", "start_preview", """{"entry":"site/index.html","root":"site"}"""),
            AgentToolCall("long", "run_command", """{"command":"curl -u alice:secret https://user:pass@example.test/file; echo sk-abcdefghijklmnopqrstuvwxyz1234567890 ${"x".repeat(400)}"}"""),
        )
        val results = listOf(
            AgentToolResult("download", "download_file", """{"path":"assets/icon.svg","sha256":"${"a".repeat(64)}","size":12}"""),
            AgentToolResult("preview", "start_preview", """{"url":"http://127.0.0.1:43210/","state":"running","entry":"site/index.html"}"""),
            AgentToolResult("long", "run_command", """{"exit_code":0}"""),
        )
        val group = (AgentActivityProjection.conversation(listOf(
            AgentMessage.assistant("", calls), AgentMessage.tools(results)))
            .single() as AgentConversationItem.Activity).group
        val text = group.operations.joinToString(" ") { "${it.title} ${it.detail}" }
        assertTrue(text.contains("assets/icon.svg"))
        assertTrue(text.contains("site/index.html"))
        assertFalse(text.contains("ultra-secret"))
        assertFalse(text.contains("alice:secret"))
        assertFalse(text.contains("user:pass"))
        assertFalse(text.contains("127.0.0.1"))
        assertFalse(text.contains("never-render"))
        assertFalse(text.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(group.operations.last().detail.length <= 260)
    }

    @Test fun liveLifecycleOverlaysOnlyCurrentObservableToolState() {
        val call = AgentToolCall("live", "run_command", """{"command":"pwd"}""")
        val items = AgentActivityProjection.conversation(
            listOf(AgentMessage.assistant("", listOf(call))),
            listOf(AgentToolLifecycle(AgentToolLifecycle.keyFor(call.id), AgentToolLifecycleStage.Started)),
        )
        val group = (items.single() as AgentConversationItem.Activity).group
        assertEquals(ActivityOutcome.Running, group.operations.single().outcome)
        assertEquals("Run 1 command (Running)", group.summary)

        val restarted = AgentActivityProjection.conversation(listOf(AgentMessage.assistant("", listOf(call))))
        assertEquals(ActivityOutcome.Unknown,
            (restarted.single() as AgentConversationItem.Activity).group.operations.single().outcome)
    }

    @Test fun structuredDownloadFailureCannotBecomeCompletedWhenErrorFlagIsMissing() {
        val call = AgentToolCall("download", "download_file",
            """{"url":"https://example.test/file","path":"existing.txt"}""")
        val result = AgentToolResult(call.id, call.name, """{"error":"destination_exists"}""", error = false)
        val group = (AgentActivityProjection.conversation(listOf(
            AgentMessage.assistant("", listOf(call)), AgentMessage.tools(listOf(result))))
            .single() as AgentConversationItem.Activity).group
        assertEquals(ActivityOutcome.Failed, group.operations.single().outcome)
        assertEquals("Download 1 file (Failed)", group.summary)
    }

    @Test fun incompleteDownloadReceiptCannotClaimSuccess() {
        val call = AgentToolCall("download", "download_file", """{"path":"target.txt"}""")
        val result = AgentToolResult(call.id, call.name, """{"path":"target.txt"}""")
        val group = (AgentActivityProjection.conversation(listOf(AgentMessage.assistant("", listOf(call)),
            AgentMessage.tools(listOf(result)))).single() as AgentConversationItem.Activity).group
        assertEquals(ActivityOutcome.Unknown, group.operations.single().outcome)
        assertFalse(group.summary.contains("Downloaded"))
    }

    @Test fun malformedArgumentAndResultFieldsRemainSafeToDisplay() {
        val calls = listOf(
            AgentToolCall("read", "read_file", """{"path":{"unexpected":"value"}}"""),
            AgentToolCall("run", "run_command", """{"command":[]}"""),
        )
        val results = listOf(
            AgentToolResult("read", "read_file", """{"error":"invalid_arguments"}""", error = true),
            AgentToolResult("run", "run_command", """{"error":"invalid_arguments","exit_code":{}}""", error = true),
        )
        val group = (AgentActivityProjection.conversation(listOf(
            AgentMessage.assistant("", calls), AgentMessage.tools(results)))
            .single() as AgentConversationItem.Activity).group
        assertTrue(group.operations.all { it.detail.isEmpty() && it.outcome == ActivityOutcome.Failed })
        assertEquals(null, group.operations.last().outcomeDetail)
        assertFalse(group.summary.contains("Ran"))
    }
}
