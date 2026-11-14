package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.subagent.PoolEntry
import com.kaiser.rivet.subagent.SubagentPoolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentsSettingsTest {
    private fun config(id: String, name: String, model: String, type: ProviderType = ProviderType.OpenAi) =
        ProviderConfig(id = id, type = type, name = name, baseUrl = "https://example.invalid/v1", model = model)

    private val configs = listOf(
        config("a", "Alpha", "alpha-1"),
        config("b", "Alpha", "alpha-2"),
        config("c", "Beta", "beta-1"),
        config("l", "Local · Qwen", "qwen.gguf", ProviderType.Local),
    )

    @Test fun summaryNamesTheCountAndWhoPicks() {
        val state = SubagentPoolState(entries = listOf(
            PoolEntry("a", "alpha-1"),
            PoolEntry("b", "alpha-2"),
            PoolEntry("c", "beta-1"),
        ))
        assertEquals("3 models · agent chooses", subagentSummary(state, configs))
        assertEquals("Off", subagentSummary(SubagentPoolState(), configs))
        assertEquals("1 model · fixed", subagentSummary(
            SubagentPoolState(allowAgentChoice = false, fixed = PoolEntry("c", "beta-1")), configs))
    }

    @Test fun rowsGroupLocalModelsFirstAndReflectTheTickState() {
        val state = SubagentPoolState(entries = listOf(PoolEntry("b", "alpha-2")))
        val groups = subagentModelRows(state, configs)
        assertEquals(listOf("On this phone", "Alpha", "Beta"), groups.map { it.first })
        assertTrue(groups[0].second.single().second.not())
        assertEquals(listOf(PoolEntry("b", "alpha-2") in state.entries), listOf(groups[1].second[1].second))
        assertFalse(groups[2].second.single().second)
        // Both configured models of one provider are separate rows: a pool row is a
        // (configId, model) pair, not a provider-wide switch.
        assertEquals(2, groups[1].second.size)
    }

    @Test fun factsAndWarningMatchTheSpec() {
        assertEquals("Depth 1 · 2 at once · at most 1 on a local model", SUBAGENT_FACTS)
        assertTrue(SUBAGENT_WARNING.contains("Subagents spend tokens too"))
        assertTrue(SUBAGENT_WARNING.contains("any model you tick"))
    }
}
