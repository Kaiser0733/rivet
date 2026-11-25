package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderVisionAttachments
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.anthropicOutputCeiling
import com.kaiser.rivet.provider.openRouterOutputCeiling
import com.kaiser.rivet.provider.resolveLimits
import com.kaiser.rivet.provider.resolvedManualMaxOutputTokens

enum class TokenEstimateSource { Reported, Estimated, Unknown }
enum class CapacitySource { ProviderMetadata, KnownTable, Learned, Manual, Local, Unknown }

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
    /**
     * True when no window was entered for this model and none was detected, so
     * [allowedInputTokens] comes from planning Rivet's 256K default at half rather than from a
     * measured window. Copy that has to print a number has to say which number it is printing.
     */
    val windowIsGuessed: Boolean = false,
) {
    /** True at the hard ceiling: the request may already be too large to send. */
    val needsReduction: Boolean get() = inputTokens != null && inputTokens >= allowedInputTokens

    /** True once the request is close enough to the ceiling to compact with room to spare. */
    val shouldCompact: Boolean get() = inputTokens != null && inputTokens >= compactAtTokens
}

internal class ContextCapacityTooSmall : Exception("context_capacity_too_small")

/** Estimates request occupancy, while keeping unverified capacity explicitly unknown. */
internal object ContextBudget {
    private const val UNKNOWN_RESERVE = 16_000
    private const val IMAGE_TOKEN_ESTIMATE = 1_024L

    fun attachedImageCount(messages: List<AgentMessage>): Int {
        val trailing = messages.lastOrNull() ?: return 0
        val trailingCount = if (trailing.role == AgentRole.User) trailing.imagePaths.size
            else ProviderVisionAttachments.trailingImageResults(messages).size
        return trailingCount + ProviderVisionAttachments.projectImageInputs(messages).size
    }

    private fun historyBytes(messages: List<AgentMessage>): Long =
        AgentContext.serializedBytes(messages).toLong() + messages.size * 64L

    fun estimateHistoryTokens(messages: List<AgentMessage>): Long =
        (historyBytes(messages) + 2) / 3 + attachedImageCount(messages) * IMAGE_TOKEN_ESTIMATE

    fun estimateRequestTokens(request: AgentRequest): Long {
        val messageBytes = historyBytes(request.messages)
        val fixedBytes = request.system.toByteArray(Charsets.UTF_8).size +
            request.model.toByteArray(Charsets.UTF_8).size + 128L
        val toolBytes = request.tools.sumOf { tool ->
            tool.name.toByteArray(Charsets.UTF_8).size.toLong() +
                tool.description.toByteArray(Charsets.UTF_8).size +
                tool.parameters.toString().toByteArray(Charsets.UTF_8).size + 96L
        }
        val imageTokens = attachedImageCount(request.messages) * IMAGE_TOKEN_ESTIMATE
        return (messageBytes + fixedBytes + toolBytes + 2) / 3 + imageTokens
    }

    fun assess(
        config: ProviderConfig,
        inputTokens: Long?,
        source: TokenEstimateSource,
        learnedPromptCap: Int? = null,
    ): ContextAssessment {
        val resolved = config.resolveLimits()
        val known = resolved.input.tokens.takeIf { resolved.inputIsTrusted }
        val guessedWindow = resolved.windowIsGuessed
        val planWindow = resolved.input.tokens.coerceAtLeast(1)
        val manualMaxOutput = config.resolvedManualMaxOutputTokens()
        val reserve = when {
            // A manual output limit at or above the window would leave no room to send
            // anything, and every request would be refused with "context too small". Half
            // the window keeps a usable input budget; the editor accepts the same pairing.
            manualMaxOutput != null -> minOf(planWindow / 2, manualMaxOutput)
            // Nothing was measured. A flat 16K reserve on a 32K guess would leave only
            // 16K of prompt, which is still too close to a 16K model's whole window;
            // scale the reserve instead.
            known == null -> minOf(UNKNOWN_RESERVE, maxOf(2_048, planWindow / 4))
            config.type == ProviderType.Gemini -> 0 // Gemini publishes a separate input limit.
            config.type == ProviderType.Anthropic -> minOf(known, anthropicOutputCeiling(config))
            config.type == ProviderType.OpenRouter -> config.openRouterOutputCeiling() ?: UNKNOWN_RESERVE
            else -> minOf(known ?: 0, minOf(UNKNOWN_RESERVE, maxOf(2048, (known ?: 0) / 4)))
        }
        var target = (planWindow - reserve).coerceAtLeast(0)
        // A learned cap is already net of output; applying it here must not subtract
        // the reserve a second time.
        if (learnedPromptCap != null && learnedPromptCap > 0) {
            target = minOf(target, learnedPromptCap)
        }
        val headroom = (target / 8).coerceAtLeast(1024)
        val capacitySource = when (resolved.input.source) {
            com.kaiser.rivet.provider.LimitSource.Learned -> CapacitySource.Learned
            com.kaiser.rivet.provider.LimitSource.KnownTable -> CapacitySource.KnownTable
            com.kaiser.rivet.provider.LimitSource.Manual -> CapacitySource.Manual
            com.kaiser.rivet.provider.LimitSource.Local -> CapacitySource.Local
            else -> if (known == null) CapacitySource.Unknown else CapacitySource.ProviderMetadata
        }
        return ContextAssessment(inputTokens, source, known,
            capacitySource,
            target, reserve,
            compactAtTokens = (target - headroom).coerceAtLeast(0),
            compactToTokens = (target - 2 * headroom).coerceAtLeast(0),
            windowIsGuessed = guessedWindow)
    }
}
