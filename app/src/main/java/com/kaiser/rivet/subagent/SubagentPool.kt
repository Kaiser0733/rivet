package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.PricingTable
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.resolvedContextWindowTokens
import java.util.Locale

/** One configured model the user has authorised for subagents. */
data class PoolEntry(
    val configId: String,
    val model: String,
)

/**
 * Persisted user choice. The pool is an allow-list, never a suggestion list:
 * an unticked provider/model cannot be selected by the coordinator.
 */
data class SubagentPoolState(
    val allowAgentChoice: Boolean = true,
    val entries: List<PoolEntry> = emptyList(),
    val fixed: PoolEntry? = null,
)

/** A pool entry after stale configs/models have been dropped. */
data class ResolvedPoolEntry(
    val alias: String,
    val entry: PoolEntry,
    val config: ProviderConfig,
    val label: String,
    val local: Boolean,
    val windowTokens: Int,
    val priceTier: PriceTier,
)

enum class PriceTier(val wire: String) {
    Unknown("unknown price"),
    Budget("budget"),
    Standard("standard"),
    Premium("premium"),
}

data class ResolvedPool(
    val entries: List<ResolvedPoolEntry>,
    val fixed: ResolvedPoolEntry? = null,
    val offLine: String? = null,
) {
    val canSpawn: Boolean
        get() = offLine == null && (fixed != null || entries.isNotEmpty())
    val aliases: List<String> get() = entries.map { it.alias }
}

sealed interface PoolValidation {
    data class Valid(val entry: ResolvedPoolEntry) : PoolValidation
    data class Refused(val line: String) : PoolValidation
}

/**
 * Pure pool resolution. Stale entries are dropped at read time so deleting a
 * provider cannot leave a runnable alias behind. Aliases are assigned from the
 * surviving order (m1, m2, ...) and are the only model selector accepted from
 * the coordinator.
 */
object SubagentPool {
    const val OFF_LINE =
        "Subagents are off: no models are ticked in Settings → Subagents."
    const val FIXED_OFF_LINE =
        "Subagents are off: choose a fixed subagent model in Settings → Subagents."

    fun resolve(state: SubagentPoolState, configs: List<ProviderConfig>): ResolvedPool {
        val byId = configs.associateBy { it.id }
        val resolved = mutableListOf<ResolvedPoolEntry>()
        for (poolEntry in state.entries) {
            val config = byId[poolEntry.configId] ?: continue
            if (config.model != poolEntry.model) continue
            if (resolved.any { it.entry == poolEntry }) continue
            resolved += resolvedEntry(
                alias = "m${resolved.size + 1}",
                entry = poolEntry,
                config = config,
                local = config.type == ProviderType.Local,
            )
        }
        val fixed = state.fixed?.let { entry ->
            val config = byId[entry.configId] ?: return@let null
            if (config.model != entry.model) null
            else resolvedEntry(
                alias = "fixed",
                entry = entry,
                config = config,
                local = config.type == ProviderType.Local,
            )
        }
        val offLine = when {
            state.allowAgentChoice && resolved.isEmpty() -> OFF_LINE
            !state.allowAgentChoice && fixed == null -> FIXED_OFF_LINE
            else -> null
        }
        return ResolvedPool(resolved, fixed, offLine)
    }

    /**
     * Resolves an alias against the current pool. Toggle OFF deliberately ignores
     * the alias and returns the fixed entry; the caller must not inspect model text.
     */
    fun validate(
        alias: String,
        state: SubagentPoolState,
        configs: List<ProviderConfig>,
    ): PoolValidation {
        val pool = resolve(state, configs)
        if (!state.allowAgentChoice) {
            return pool.fixed?.let { PoolValidation.Valid(it) }
                ?: PoolValidation.Refused(pool.offLine ?: FIXED_OFF_LINE)
        }
        if (pool.entries.isEmpty()) {
            return PoolValidation.Refused(pool.offLine ?: OFF_LINE)
        }
        pool.entries.firstOrNull { it.alias == alias }?.let { return PoolValidation.Valid(it) }
        val valid = pool.aliases.joinToString(", ").ifBlank { "none" }
        return PoolValidation.Refused(
            "Unknown subagent model '$alias'. Valid aliases: $valid."
        )
    }

    private fun resolvedEntry(
        alias: String,
        entry: PoolEntry,
        config: ProviderConfig,
        local: Boolean,
    ): ResolvedPoolEntry {
        val window = config.resolvedContextWindowTokens(entry.model)
        val tier = priceTier(entry.model)
        val provider = config.name.ifBlank { config.type.name }
        val label = buildString {
            append(provider).append(" · ").append(entry.model)
            append(" · ").append(windowLabel(window))
            append(" · ").append(tier.wire)
            if (local) append(" · local")
        }
        return ResolvedPoolEntry(alias, entry, config, label, local, window, tier)
    }

    private fun priceTier(model: String): PriceTier {
        val pricing = PricingTable.price(model) ?: return PriceTier.Unknown
        val output = pricing.outputPerMillionUsd
        return when {
            output <= 1.0 -> PriceTier.Budget
            output <= 8.0 -> PriceTier.Standard
            else -> PriceTier.Premium
        }
    }

    private fun windowLabel(tokens: Int): String {
        val thousands = tokens / 1_000.0
        val text = when {
            thousands >= 100.0 -> thousands.toInt().toString()
            thousands % 1.0 == 0.0 -> thousands.toInt().toString()
            else -> String.format(Locale.US, "%.1f", thousands)
        }
        return "${text}k"
    }
}
