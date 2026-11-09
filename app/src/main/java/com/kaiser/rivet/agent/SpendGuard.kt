package com.kaiser.rivet.agent

import java.util.Locale

/** Per-million-token list price. Small, static, and deliberately not a promise. */
data class ModelPricing(
    val inputPerMillionUsd: Double,
    val outputPerMillionUsd: Double,
    val cacheReadPerMillionUsd: Double = inputPerMillionUsd,
)

object PricingTable {
    private val known = linkedMapOf(
        "gpt-4o-mini" to ModelPricing(0.15, 0.60),
        "gpt-4o" to ModelPricing(2.50, 10.00),
        "gpt-4.1-mini" to ModelPricing(0.40, 1.60),
        "gpt-4.1" to ModelPricing(2.00, 8.00),
        "o4-mini" to ModelPricing(1.10, 4.40),
        "claude-3-5-haiku" to ModelPricing(0.80, 4.00),
        "claude-3-5-sonnet" to ModelPricing(3.00, 15.00),
        "claude-3-7-sonnet" to ModelPricing(3.00, 15.00),
        "claude-sonnet-4" to ModelPricing(3.00, 15.00),
        "gemini-2.0-flash" to ModelPricing(0.10, 0.40),
        "gemini-1.5-pro" to ModelPricing(1.25, 5.00),
        "deepseek-chat" to ModelPricing(0.27, 1.10),
        "deepseek-reasoner" to ModelPricing(0.55, 2.19),
    )

    fun price(model: String): ModelPricing? {
        val normalized = model.lowercase(Locale.ROOT)
        return known.entries.firstOrNull { normalized.contains(it.key) }?.value
    }
}

data class SpendCaps(
    val perRunUsd: Double? = null,
    val monthlyUsd: Double? = null,
    // When a model has no published price, a cap can still be token-based.
    val perRunTokens: Long? = null,
    val monthlyTokens: Long? = null,
)

enum class SpendStop { PerRun, Monthly }

data class SpendDecision(
    val stop: SpendStop?,
    val runCostUsd: Double?,
    val monthlyCostUsd: Double?,
    val runTokens: Long,
    val monthlyTokens: Long,
    val message: String,
) {
    val paused: Boolean get() = stop != null
}

object SpendGuard {
    fun tokens(usage: AgentUsage): Long =
        usage.totalTokens ?: ((usage.inputTokens ?: 0) + (usage.outputTokens ?: 0))

    fun cost(usage: AgentUsage, pricing: ModelPricing?, userPricePerMillionUsd: Double? = null): Double? {
        val p = pricing ?: userPricePerMillionUsd?.let { ModelPricing(it, it * 2) } ?: return null
        val input = usage.inputTokens ?: 0
        val cached = usage.cacheReadTokens ?: 0
        val output = (usage.outputTokens ?: 0) + (usage.reasoningTokens ?: 0)
        return (input * p.inputPerMillionUsd + cached * p.cacheReadPerMillionUsd +
            output * p.outputPerMillionUsd) / 1_000_000.0
    }

    fun evaluate(
        runCost: Double?,
        monthCost: Double?,
        runTokens: Long,
        monthTokens: Long,
        caps: SpendCaps,
        localModel: Boolean = false,
    ): SpendDecision {
        if (localModel) return SpendDecision(null, 0.0, 0.0, 0, 0, "local model — no spend")
        val runStop = caps.perRunUsd?.let { cap -> runCost?.let { it >= cap } == true } == true
        val monthStop = caps.monthlyUsd?.let { cap -> monthCost?.let { it >= cap } == true } == true
        val runTokenStop = caps.perRunTokens?.let { runTokens >= it } == true
        val monthTokenStop = caps.monthlyTokens?.let { monthTokens >= it } == true
        val stop = when {
            runStop || runTokenStop -> SpendStop.PerRun
            monthStop || monthTokenStop -> SpendStop.Monthly
            else -> null
        }
        return SpendDecision(stop, runCost, monthCost, runTokens, monthTokens,
            if (stop == null) "within budget"
            else "budget reached — raise or stop")
    }

    /** A range, never false precision; unknown pricing says so. */
    fun estimateRange(pricing: ModelPricing?, inputTokens: LongRange, outputTokens: LongRange,
                      userPricePerMillionUsd: Double? = null): String {
        val p = pricing ?: userPricePerMillionUsd?.let { ModelPricing(it, it * 2) }
        if (p == null) return "unknown pricing"
        val low = (inputTokens.first * p.inputPerMillionUsd +
            outputTokens.first * p.outputPerMillionUsd) / 1_000_000.0
        val high = (inputTokens.last * p.inputPerMillionUsd +
            outputTokens.last * p.outputPerMillionUsd) / 1_000_000.0
        return "~$%.2f–$%.2f (estimate)".format(Locale.US, low, high)
    }

    fun liveLabel(costUsd: Double?): String? =
        costUsd?.takeIf { it > 0 }?.let { "$%.2f".format(Locale.US, it) }

    /**
     * A deliberately coarse token estimate used when a provider omits usage. Four
     * characters per token is the usual English/code rule of thumb; rounding up keeps
     * the cap from being under-counted by a few tokens.
     */
    fun estimatedUsage(inputChars: Long, outputChars: Long): AgentUsage {
        val input = ((inputChars.coerceAtLeast(0L) + 3L) / 4L).coerceAtLeast(1L)
        val output = ((outputChars.coerceAtLeast(0L) + 3L) / 4L).coerceAtLeast(1L)
        return AgentUsage(inputTokens = input, outputTokens = output,
            totalTokens = input + output)
    }
}
