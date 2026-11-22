package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.resolvedContextWindowTokens
import com.kaiser.rivet.provider.trustedInputLimitTokens
import kotlin.math.roundToInt

data class ContextMeterReading(
    val providerId: String,
    val model: String,
    val endpoint: String,
    val reasoning: ReasoningLevel,
    val usedTokens: Long,
    val contextWindow: Int,
    val estimatedWindow: Boolean,
) {
    val percent: Int get() = contextPercent(usedTokens, contextWindow)

    internal fun matches(config: ProviderConfig): Boolean =
        providerId == config.id && model == config.model && endpoint == config.baseUrl &&
            reasoning == config.reasoning && contextWindow == config.resolvedContextWindowTokens() &&
            estimatedWindow == (config.trustedInputLimitTokens() == null)
}

internal fun contextReading(config: ProviderConfig, tokens: Long): ContextMeterReading =
    ContextMeterReading(config.id, config.model, config.baseUrl, config.reasoning, tokens,
        config.resolvedContextWindowTokens(), config.trustedInputLimitTokens() == null)

internal fun contextPercent(usedTokens: Long, contextWindow: Int): Int =
    (usedTokens.coerceAtLeast(0L).toDouble() / contextWindow.coerceAtLeast(1) * 100)
        .coerceIn(0.0, 100.0).roundToInt()

internal fun contextReadingForRequest(config: ProviderConfig, request: AgentRequest): ContextMeterReading =
    contextReading(config, ContextBudget.estimateRequestTokens(request))
