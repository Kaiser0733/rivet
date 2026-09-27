package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.anthropicOutputCeiling
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
) {
    val needsReduction: Boolean get() = inputTokens != null && inputTokens >= allowedInputTokens
}

internal class ContextCapacityTooSmall : Exception("context_capacity_too_small")

/** Estimates request occupancy, while keeping unverified capacity explicitly unknown. */
internal object ContextBudget {
    // Unknown capacity is a planning threshold, not a claimed model limit.
    private const val UNKNOWN_PLANNING_LIMIT = 64_000
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
        val baseReserve = if (known == null) UNKNOWN_RESERVE else
            maxOf(2048, minOf(12_000, known / 3))
        val reserve = when {
            known == null -> UNKNOWN_RESERVE
            config.type == ProviderType.Gemini -> 0 // Gemini publishes a separate input limit.
            config.type == ProviderType.Anthropic -> minOf(known, anthropicOutputCeiling(config))
            else -> minOf(known, baseReserve)
        }
        val target = ((known ?: UNKNOWN_PLANNING_LIMIT) - reserve).coerceAtLeast(0)
        return ContextAssessment(inputTokens, source, known,
            if (known == null) CapacitySource.Unknown else CapacitySource.ProviderMetadata,
            target, reserve)
    }
}
