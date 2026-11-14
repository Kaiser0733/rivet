package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.ProjectContext
import com.kaiser.rivet.agent.modelMessages
import com.kaiser.rivet.agent.validatedContext
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentContextTest {
    private val workspace = "content://provider/tree/project"
    private val cloudConfig = ProviderConfig(
        id = "cfg-sonnet",
        type = ProviderType.Anthropic,
        name = "Claude",
        baseUrl = ProviderType.Anthropic.defaultBaseUrl,
        model = "claude-sonnet-4-6",
    )
    private val localConfig = ProviderConfig(
        id = "cfg-qwen",
        type = ProviderType.Local,
        name = "Local · Qwen 2.5 Coder 3B",
        baseUrl = ProviderType.Local.defaultBaseUrl,
        model = "qwen/qwen-3b.gguf",
    )

    @Test
    fun emptyPoolRendersOffLineAndNonEmptyPoolRendersAliasesRulesAndLocalNote() {
        val emptyText = SubagentContext.poolContent(SubagentPoolState(), listOf(cloudConfig))
        assertEquals(SubagentPool.OFF_LINE, emptyText)

        val activeState = SubagentPoolState(
            allowAgentChoice = true,
            entries = listOf(
                PoolEntry(cloudConfig.id, cloudConfig.model),
                PoolEntry(localConfig.id, localConfig.model),
            ),
        )
        val activeText = SubagentContext.poolContent(
            state = activeState,
            configs = listOf(cloudConfig, localConfig),
            coordinatorConfig = localConfig,
        )
        assertTrue(activeText.contains("claude-sonnet-4-6"))
        assertTrue(activeText.contains("qwen-3b.gguf"))
        assertTrue(activeText.contains("max 2 running, at most 1 local"))
        assertTrue(activeText.contains("time-shares one slot with this local coordinator"))
        assertTrue(activeText.contains("1. Do simple or 1–2 file work yourself."))
        assertTrue(activeText.contains("5. Never re-run a worker's commands"))
    }

    @Test
    fun poolUpdatesAreDigestDiffedAndModelMessagesKeepsOnlyNewestPoolAndAllResults() {
        val initial = SubagentContext.poolUpdates(emptyList(), workspace, SubagentPoolState(), listOf(cloudConfig))
        assertEquals(1, initial.size)
        assertNotNull(initial.single().validatedContext())

        // Unchanged pool state emits nothing.
        val unchanged = SubagentContext.poolUpdates(initial, workspace, SubagentPoolState(), listOf(cloudConfig))
        assertTrue(unchanged.isEmpty())

        // Changing pool state emits a new snapshot; modelMessages keeps only the newest pool snapshot.
        val nextState = SubagentPoolState(
            allowAgentChoice = false,
            fixed = PoolEntry(cloudConfig.id, cloudConfig.model),
        )
        val updated = SubagentContext.poolUpdates(initial, workspace, nextState, listOf(cloudConfig))
        assertEquals(1, updated.size)

        val result1 = SubagentContext.resultEvent(workspace, "w-1", "Check auth", """{"worker_id":"w-1"}""")
        val result2 = SubagentContext.resultEvent(
            workspace,
            "w-2",
            "Check api",
            """{"worker_id":"w-2","secret":"sk-abcdefghijklmnopqrstuv"}""",
        )
        val transcript = initial + updated + result1 + result2
        val wire = modelMessages(transcript, workspace, enforceWorkspace = true)

        // 1 newest pool snapshot + 2 result events = 3 model messages.
        assertEquals(3, wire.size)
        assertFalse(wire[0].text.contains(SubagentPool.OFF_LINE))
        assertTrue(wire[0].text.contains("Mode: fixed:"))
        assertTrue(wire[1].text.contains("Subagent finished (w-1 · Check auth)"))
        assertTrue(wire[2].text.contains("Subagent finished (w-2 · Check api)"))
        assertFalse("Secrets in result envelopes must be redacted", wire[2].text.contains("sk-abcdefghijklmnopqrstuv"))

        // Compaction projection retains the newest subagent_pool snapshot.
        val projected = ProjectContext.projection(
            source = initial + updated + AgentMessage.user("keep"),
            retained = listOf(AgentMessage.user("keep")),
            summary = "summary",
            workspaceOverride = workspace,
        )
        val poolEventsInProjected = projected.filter { it.validatedContext()?.kind == SubagentContext.POOL_KIND }
        assertEquals(1, poolEventsInProjected.size)
        assertEquals(updated.single().validatedContext()?.digest, poolEventsInProjected.single().validatedContext()?.digest)
    }
}
