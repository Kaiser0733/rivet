package com.kaiser.rivet.storage

import android.content.Context
import com.kaiser.rivet.agent.SpendCaps
import java.time.YearMonth
import java.util.Locale

/**
 * Small, synchronous ledger for the spend guard. Values are display/limit inputs, not
 * billing records: provider usage remains the source of truth for tokens.
 */
class SpendStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("spend_guard", Context.MODE_PRIVATE)

    @Synchronized
    fun caps(): SpendCaps = SpendCaps(
        perRunUsd = decimal(PER_RUN_USD),
        monthlyUsd = decimal(MONTHLY_USD),
        perRunTokens = long(PER_RUN_TOKENS),
        monthlyTokens = long(MONTHLY_TOKENS),
    )

    @Synchronized
    fun userPricePerMillionUsd(): Double? = decimal(USER_PRICE)

    @Synchronized
    fun save(caps: SpendCaps, userPricePerMillionUsd: Double?) {
        preferences.edit()
            .putString(PER_RUN_USD, caps.perRunUsd?.text())
            .putString(MONTHLY_USD, caps.monthlyUsd?.text())
            .putString(PER_RUN_TOKENS, caps.perRunTokens?.toString())
            .putString(MONTHLY_TOKENS, caps.monthlyTokens?.toString())
            .putString(USER_PRICE, userPricePerMillionUsd?.text())
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
        return (decimal(MONTH_USD) ?: 0.0) to (long(MONTH_TOKENS) ?: 0L)
    }

    @Synchronized
    fun add(costUsd: Double, tokens: Long) {
        val key = YearMonth.now().toString()
        val stale = preferences.getString(MONTH_KEY, null) != key
        val oldCost = if (stale) 0.0 else decimal(MONTH_USD) ?: 0.0
        val oldTokens = if (stale) 0L else long(MONTH_TOKENS) ?: 0L
        preferences.edit().putString(MONTH_KEY, key)
            .putString(MONTH_USD, (oldCost + costUsd).text())
            .putString(MONTH_TOKENS, (oldTokens + tokens).toString())
            .apply()
    }

    private fun decimal(key: String): Double? =
        preferences.getString(key, null)?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

    private fun long(key: String): Long? =
        preferences.getString(key, null)?.toLongOrNull()?.takeIf { it > 0 }

    private fun Double.text(): String = String.format(Locale.US, "%.6f", this)

    companion object {
        private const val PER_RUN_USD = "per_run_usd"
        private const val MONTHLY_USD = "monthly_usd"
        private const val PER_RUN_TOKENS = "per_run_tokens"
        private const val MONTHLY_TOKENS = "monthly_tokens"
        private const val USER_PRICE = "user_price_per_million"
        private const val MONTH_KEY = "month_key"
        private const val MONTH_USD = "month_usd"
        private const val MONTH_TOKENS = "month_tokens"

        fun isLocal(baseUrl: String): Boolean = LocalEndpointPolicy.isLocal(baseUrl)
    }
}
