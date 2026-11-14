package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.storage.SpendStore

data class RunSpendUpdate(
    val decision: SpendDecision,
    val label: String?,
    val spendFromEstimate: Boolean,
    val runSpendUsd: Double,
    val shouldPause: Boolean,
    val local: Boolean = false,
)

/** Synchronized run and monthly spend ledger shared by the coordinator and workers. */
class RunSpendLedger(
    private val addMonthlySpend: (costUsd: Double, tokens: Long) -> Unit,
    private val readCaps: () -> SpendCaps,
    private val readUserPricePerMillionUsd: () -> Double?,
    private val isLocalEndpoint: (String) -> Boolean = { SpendStore.isLocal(it) },
    private val onBudgetPause: () -> Unit = {},
) {
    constructor(
        spendStore: SpendStore,
        readCaps: () -> SpendCaps = { spendStore.caps() },
        readUserPricePerMillionUsd: () -> Double? = { spendStore.userPricePerMillionUsd() },
        onBudgetPause: () -> Unit = {},
    ) : this(
        addMonthlySpend = { cost, tokens -> spendStore.add(cost, tokens) },
        readCaps = readCaps,
        readUserPricePerMillionUsd = readUserPricePerMillionUsd,
        isLocalEndpoint = { SpendStore.isLocal(it) },
        onBudgetPause = onBudgetPause,
    )

    private val lock = Any()
    private var runSpendUsd = 0.0
    private var runSpendTokens = 0L
    private var monthSpendUsd = 0.0
    private var monthSpendTokens = 0L
    private var runCapOverrideUsd: Double? = null
    private var monthCapOverrideUsd: Double? = null
    private var runTokenOverride: Long? = null
    private var monthTokenOverride: Long? = null
    @Volatile var budgetPaused: Boolean = false
        private set

    fun syncMonth(monthTotals: Pair<Double, Long>) {
        synchronized(lock) {
            monthSpendUsd = monthTotals.first
            monthSpendTokens = monthTotals.second
        }
    }

    fun resetRun(monthTotals: Pair<Double, Long>) {
        synchronized(lock) {
            runSpendUsd = 0.0
            runSpendTokens = 0L
            runCapOverrideUsd = null
            monthCapOverrideUsd = null
            runTokenOverride = null
            monthTokenOverride = null
            budgetPaused = false
            monthSpendUsd = monthTotals.first
            monthSpendTokens = monthTotals.second
        }
    }

    fun raiseCapForCurrentRun(): Boolean {
        synchronized(lock) {
            if (!budgetPaused) return false
            val caps = effectiveCapsLocked()
            runCapOverrideUsd = maxOf(runSpendUsd * 1.5, (caps.perRunUsd ?: 0.0) * 2, 1.0)
            monthCapOverrideUsd = maxOf(monthSpendUsd * 1.5, (caps.monthlyUsd ?: 0.0) * 2, 1.0)
            runTokenOverride = maxOf(runSpendTokens * 2, (caps.perRunTokens ?: 0L) * 2, 1L)
            monthTokenOverride = maxOf(monthSpendTokens * 2, (caps.monthlyTokens ?: 0L) * 2, 1L)
            budgetPaused = false
            return true
        }
    }

    fun record(config: ProviderConfig, usage: AgentUsage, estimated: Boolean): RunSpendUpdate {
        if (isLocalEndpoint(config.baseUrl)) {
            return synchronized(lock) {
                val hasCloudSpend = runSpendTokens > 0L || runSpendUsd > 0.0
                RunSpendUpdate(
                    decision = SpendGuard.evaluate(
                        runSpendUsd, monthSpendUsd, runSpendTokens, monthSpendTokens,
                        effectiveCapsLocked(), localModel = true,
                    ),
                    label = if (hasCloudSpend) SpendGuard.liveLabel(runSpendUsd) else null,
                    spendFromEstimate = false,
                    runSpendUsd = runSpendUsd,
                    shouldPause = false,
                    local = true,
                )
            }
        }
        val tokens = SpendGuard.tokens(usage)
        val cost = SpendGuard.cost(usage, PricingTable.price(config.model), readUserPricePerMillionUsd())
        var firePause = false
        val update = synchronized(lock) {
            runSpendTokens += tokens
            monthSpendTokens += tokens
            if (cost != null) {
                runSpendUsd += cost
                monthSpendUsd += cost
            }
            addMonthlySpend(cost ?: 0.0, tokens)
            val decision = SpendGuard.evaluate(
                runSpendUsd, monthSpendUsd, runSpendTokens, monthSpendTokens, effectiveCapsLocked(),
            )
            val label = if (cost == null) "unknown pricing" else SpendGuard.liveLabel(runSpendUsd)
            if (decision.paused && !budgetPaused) {
                budgetPaused = true
                firePause = true
            }
            RunSpendUpdate(
                decision = decision,
                label = label,
                spendFromEstimate = estimated && cost != null,
                runSpendUsd = runSpendUsd,
                shouldPause = firePause,
                local = false,
            )
        }
        if (firePause) onBudgetPause()
        return update
    }

    private fun effectiveCapsLocked(): SpendCaps {
        val caps = readCaps()
        return caps.copy(
            perRunUsd = runCapOverrideUsd ?: caps.perRunUsd,
            monthlyUsd = monthCapOverrideUsd ?: caps.monthlyUsd,
            perRunTokens = runTokenOverride ?: caps.perRunTokens,
            monthlyTokens = monthTokenOverride ?: caps.monthlyTokens,
        )
    }
}
