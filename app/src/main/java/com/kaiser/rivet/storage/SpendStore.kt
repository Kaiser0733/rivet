package com.kaiser.rivet.storage

import android.content.Context
import com.kaiser.rivet.agent.SpendCaps
import java.time.YearMonth

/**
 * Small, synchronous ledger for the spend guard. Values are display/limit inputs, not
 * billing records: provider usage remains the source of truth for tokens.
 */
class SpendStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("spend_guard", Context.MODE_PRIVATE)

    @Synchronized
    fun caps(): SpendCaps {
        if (CAP_KEYS.none(preferences::contains)) return SpendCaps.DEFAULT
        return SpendCaps(
            perRunUsd = decimal(PER_RUN_USD),
            monthlyUsd = decimal(MONTHLY_USD),
            perRunTokens = long(PER_RUN_TOKENS),
            monthlyTokens = long(MONTHLY_TOKENS),
        )
    }

    @Synchronized
    fun userPricePerMillionUsd(): Double? = decimal(USER_PRICE)

    @Synchronized
    fun save(caps: SpendCaps, userPricePerMillionUsd: Double?) {
        preferences.edit()
            .putString(PER_RUN_USD, caps.perRunUsd?.takeIf { it.isFinite() }?.toString() ?: OFF)
            .putString(MONTHLY_USD, caps.monthlyUsd?.takeIf { it.isFinite() }?.toString() ?: OFF)
            .putString(PER_RUN_TOKENS, caps.perRunTokens?.toString() ?: OFF)
            .putString(MONTHLY_TOKENS, caps.monthlyTokens?.toString() ?: OFF)
            .putString(USER_PRICE, userPricePerMillionUsd?.takeIf { it.isFinite() }?.toString())
            .apply()
    }

    /** Returns (cost, tokens) for the current calendar month, resetting a stale month. */
    @Synchronized
    fun month(): Pair<Double, Long> {
        val key = YearMonth.now().toString()
        if (preferences.getString(MONTH_KEY, null) != key) {
            preferences.edit().putString(MONTH_KEY, key)
                .putString(MONTH_USD, null).putString(MONTH_TOKENS, null).apply()
            return 0.0 to 0L
        }
        return (decimal(MONTH_USD) ?: 0.0) to (nonNegativeLong(MONTH_TOKENS) ?: 0L)
    }

    @Synchronized
    fun add(costUsd: Double, tokens: Long) {
        val safeCost = costUsd.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val safeTokens = tokens.coerceAtLeast(0L)
        val key = YearMonth.now().toString()
        val stale = preferences.getString(MONTH_KEY, null) != key
        val oldCost = if (stale) 0.0 else decimal(MONTH_USD) ?: 0.0
        val oldTokens = if (stale) 0L else nonNegativeLong(MONTH_TOKENS) ?: 0L
        preferences.edit().putString(MONTH_KEY, key)
            .putString(MONTH_USD, (oldCost + safeCost).toString())
            .putString(MONTH_TOKENS, (oldTokens + safeTokens).toString())
            .apply()
    }

    @Synchronized
    fun runTotals(runId: String): Pair<Double, Long> =
        (decimal("run_usd_$runId") ?: 0.0) to (nonNegativeLong("run_tokens_$runId") ?: 0L)

    @Synchronized
    fun saveRunTotals(runId: String, costUsd: Double, tokens: Long) {
        val current = runTotals(runId)
        preferences.edit()
            .putString("run_usd_$runId", maxOf(current.first, costUsd).toString())
            .putString("run_tokens_$runId", maxOf(current.second, tokens).toString())
            .commit()
    }

    @Synchronized
    fun clearRunTotals(runId: String) {
        preferences.edit().remove("run_usd_$runId").remove("run_tokens_$runId").apply()
    }

    private fun decimal(key: String): Double? =
        preferences.getString(key, null)?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

    private fun long(key: String): Long? =
        preferences.getString(key, null)?.toLongOrNull()?.takeIf { it > 0 }

    private fun nonNegativeLong(key: String): Long? =
        preferences.getString(key, null)?.toLongOrNull()?.takeIf { it >= 0 }

    companion object {
        private const val PER_RUN_USD = "per_run_usd"
        private const val MONTHLY_USD = "monthly_usd"
        private const val PER_RUN_TOKENS = "per_run_tokens"
        private const val MONTHLY_TOKENS = "monthly_tokens"
        private const val USER_PRICE = "user_price_per_million"
        private const val MONTH_KEY = "month_key"
        private const val MONTH_USD = "month_usd"
        private const val MONTH_TOKENS = "month_tokens"
        private const val OFF = "off"
        private val CAP_KEYS = listOf(PER_RUN_USD, MONTHLY_USD, PER_RUN_TOKENS, MONTHLY_TOKENS)

        fun isLocal(baseUrl: String): Boolean = LocalEndpointPolicy.isLocal(baseUrl)
    }
}
