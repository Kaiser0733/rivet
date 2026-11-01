package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.DEFAULT_CONTEXT_WINDOW_TOKENS
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.anthropicOutputCeiling
import com.kaiser.rivet.provider.openRouterOutputCeiling
import com.kaiser.rivet.provider.resolvedContextWindowTokens
import com.kaiser.rivet.provider.resolvedManualMaxOutputTokens
import com.kaiser.rivet.provider.trustedInputLimitTokens

enum class TokenEstimateSource { Reported, Estimated, Unknown }
enum class CapacitySource { ProviderMetadata, Unknown }

data class ContextAssessment(
    val inputTokens: Long?,
    val estimateSource: TokenEstimateSource,
    val knownInputLimitTokens: Int?,
    val capacitySource: CapacitySource,
    val allowedInputTokens: Int,
    val reservedTokens: Int,
    // Compacting exactly at the ceiling leaves no room to send the compacted request:
    // the next step re-triggers, and any estimation slack fails the whole turn. The
    // trigger sits a margin below the ceiling and the plan aims a margin below that,
    // the same two-margin scheme dsh uses (thresholdRatio .8 plus a 64k headroom).
    val compactAtTokens: Int = allowedInputTokens,
    val compactToTokens: Int = allowedInputTokens,
) {
    /** True at the hard ceiling: the request may already be too large to send. */
    val needsReduction: Boolean get() = inputTokens != null && inputTokens >= allowedInputTokens

    /** True once the request is close enough to the ceiling to compact with room to spare. */
    val shouldCompact: Boolean get() = inputTokens != null && inputTokens >= compactAtTokens
}

internal class ContextCapacityTooSmall : Exception("context_capacity_too_small")

/** Estimates request occupancy, while keeping unverified capacity explicitly unknown. */
internal object ContextBudget {
    private const val DEFAULT_PLANNING_LIMIT = DEFAULT_CONTEXT_WINDOW_TOKENS
    private const val UNKNOWN_RESERVE = 16_000

    fun estimateRequestTokens(request: AgentRequest): Long {
        val messageBytes = AgentContext.serializedBytes(request.messages).toLong()
        val fixedBytes = request.system.toByteArray(Charsets.UTF_8).size +
            request.model.toByteArray(Charsets.UTF_8).size + 128L
        val toolBytes = request.tools.sumOf { tool ->
            tool.name.toByteArray(Charsets.UTF_8).size.toLong() +
                tool.description.toByteArray(Charsets.UTF_8).size +
                tool.parameters.toString().toByteArray(Charsets.UTF_8).size + 96L
        }
        val framingBytes = request.messages.size * 64L
        return (messageBytes + fixedBytes + toolBytes + framingBytes + 2) / 3
    }

    fun assess(config: ProviderConfig, inputTokens: Long?, source: TokenEstimateSource): ContextAssessment {
        val known = config.trustedInputLimitTokens()
        val resolvedWindow = config.resolvedContextWindowTokens().takeIf { it > 0 } ?: DEFAULT_PLANNING_LIMIT
        val manualMaxOutput = config.resolvedManualMaxOutputTokens()
        val reserve = when {
            // A manual output limit at or above the window would leave no room to send
            // anything, and every request would be refused with "context too small". Half
            // the window keeps a usable input budget; the editor accepts the same pairing.
            manualMaxOutput != null -> minOf(resolvedWindow / 2, manualMaxOutput)
            known == null -> UNKNOWN_RESERVE
            config.type == ProviderType.Gemini -> 0 // Gemini publishes a separate input limit.
            config.type == ProviderType.Anthropic -> minOf(known, anthropicOutputCeiling(config))
            config.type == ProviderType.OpenRouter -> config.openRouterOutputCeiling() ?: UNKNOWN_RESERVE
            else -> minOf(known, minOf(UNKNOWN_RESERVE, maxOf(2048, known / 4)))
        }
        val target = (resolvedWindow - reserve).coerceAtLeast(0)
        val headroom = (target / 8).coerceAtLeast(1024)
        return ContextAssessment(inputTokens, source, known,
            if (known == null) CapacitySource.Unknown else CapacitySource.ProviderMetadata,
            target, reserve,
            compactAtTokens = (target - headroom).coerceAtLeast(0),
            compactToTokens = (target - 2 * headroom).coerceAtLeast(0))
    }
}
