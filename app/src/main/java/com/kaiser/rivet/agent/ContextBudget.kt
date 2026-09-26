package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
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

/** Estimates request occupancy, while keeping unverified capacity explicitly unknown. */
internal object ContextBudget {
    private const val UNKNOWN_PLANNING_LIMIT = 32_000
    private const val UNKNOWN_RESERVE = 12_000

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
        val reserve = if (known == null) UNKNOWN_RESERVE else
            minOf(known / 2, maxOf(2048, minOf(12_000, known / 3)))
        val target = ((known ?: UNKNOWN_PLANNING_LIMIT) - reserve).coerceAtLeast(1)
        return ContextAssessment(inputTokens, source, known,
            if (known == null) CapacitySource.Unknown else CapacitySource.ProviderMetadata,
            target, reserve)
    }
}
