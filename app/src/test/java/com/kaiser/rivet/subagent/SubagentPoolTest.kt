package com.kaiser.rivet.subagent

import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentPoolTest {
    private fun cloud(id: String, model: String, name: String = "OpenAI") = ProviderConfig(
        id = id,
        type = ProviderType.OpenAi,
        name = name,
        baseUrl = "https://example.invalid/v1",
        model = model,
    )

    private fun local(id: String, model: String) = ProviderConfig(
        id = id,
        type = ProviderType.Local,
        name = "On this phone",
        baseUrl = "local://",
        model = model,
    )

    @Test fun resolvesLiveEntriesWithStableAliasesAndHonestLabels() {
        val configs = listOf(
            cloud("c1", "gpt-4o-mini"),
            cloud("c2", "claude-sonnet-4", name = "Anthropic"),
            local("l1", "qwen-1.7b"),
        )
        val state = SubagentPoolState(entries = listOf(
            PoolEntry("c1", "gpt-4o-mini"),
            PoolEntry("c2", "claude-sonnet-4"),
            PoolEntry("l1", "qwen-1.7b"),
        ))

        val resolved = SubagentPool.resolve(state, configs)
        assertNull(resolved.offLine)
        assertTrue(resolved.canSpawn)
        assertEquals(listOf("m1", "m2", "m3"), resolved.aliases)
        assertTrue(resolved.entries[0].label.contains("OpenAI"))
        assertTrue(resolved.entries[0].label.contains("gpt-4o-mini"))
        assertTrue(resolved.entries[0].label.contains("262k"))
        assertTrue(resolved.entries[0].label.contains("budget"))
        assertTrue(resolved.entries[2].label.contains("local"))
    }

    @Test fun dropsDeletedConfigsAndChangedModels() {
        val configs = listOf(cloud("c1", "gpt-4o-mini"))
        val state = SubagentPoolState(entries = listOf(
            PoolEntry("missing", "gone"),
            PoolEntry("c1", "no-longer-the-config-model"),
            PoolEntry("c1", "gpt-4o-mini"),
        ))

        val resolved = SubagentPool.resolve(state, configs)
        assertEquals(1, resolved.entries.size)
        assertEquals("m1", resolved.entries.single().alias)
        assertEquals("gpt-4o-mini", resolved.entries.single().entry.model)
    }

    @Test fun emptyPoolIsOffWithTheOneLineReason() {
        val resolved = SubagentPool.resolve(SubagentPoolState(), emptyList())
        assertEquals(SubagentPool.OFF_LINE, resolved.offLine)
        assertFalse(resolved.canSpawn)
        val validation = SubagentPool.validate("m1", SubagentPoolState(), emptyList())
        assertTrue(validation is PoolValidation.Refused)
        assertEquals(SubagentPool.OFF_LINE, (validation as PoolValidation.Refused).line)
    }

    @Test fun toggleOffUsesFixedEntryAndIgnoresAlias() {
        val configs = listOf(cloud("c1", "gpt-4o-mini"), cloud("c2", "gpt-4o"))
        val fixed = SubagentPoolState(
            allowAgentChoice = false,
            fixed = PoolEntry("c2", "gpt-4o"),
        )
        val validation = SubagentPool.validate("not-an-alias", fixed, configs)
        assertTrue(validation is PoolValidation.Valid)
        assertEquals("gpt-4o", (validation as PoolValidation.Valid).entry.entry.model)
        assertEquals("fixed", validation.entry.alias)
    }

    @Test fun toggleOffWithoutFixedIsOff() {
        val state = SubagentPoolState(allowAgentChoice = false)
        val resolved = SubagentPool.resolve(state, listOf(cloud("c1", "gpt-4o-mini")))
        assertEquals(SubagentPool.FIXED_OFF_LINE, resolved.offLine)
        assertFalse(resolved.canSpawn)
        assertTrue(SubagentPool.validate("m1", state, listOf(cloud("c1", "gpt-4o-mini")))
            is PoolValidation.Refused)
    }

    @Test fun unknownAliasListsTheValidAliases() {
        val configs = listOf(cloud("c1", "gpt-4o-mini"))
        val state = SubagentPoolState(entries = listOf(PoolEntry("c1", "gpt-4o-mini")))
        val validation = SubagentPool.validate("gpt-4o-mini", state, configs)
        assertTrue(validation is PoolValidation.Refused)
        val line = (validation as PoolValidation.Refused).line
        assertTrue(line.contains("m1"))
        assertFalse(line.contains("Subagents are off"))
        assertNotNull(SubagentPool.validate("m1", state, configs) as? PoolValidation.Valid)
    }

    @Test fun aPriceTierIsUnknownWhenPricingIsUnknown() {
        val resolved = SubagentPool.resolve(
            SubagentPoolState(entries = listOf(PoolEntry("c1", "made-up-model"))),
            listOf(cloud("c1", "made-up-model")),
        )
        assertEquals(PriceTier.Unknown, resolved.entries.single().priceTier)
        assertTrue(resolved.entries.single().label.contains("unknown price"))
    }
}
