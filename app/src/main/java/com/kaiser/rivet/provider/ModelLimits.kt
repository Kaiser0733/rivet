package com.kaiser.rivet.provider

import java.util.Locale

enum class LimitSource { Manual, Learned, Provider, KnownTable, UsageFloor, Default, Local }

enum class LimitSemantics { InputOnly, TotalWindow, Unknown }

enum class LimitWarning {
    ManualAboveProvider,
    ManualAboveLocalCeiling,
    LearnedBelowManual,
    DefaultAssumed,
    LocalClamped,
}

data class ResolvedLimit(
    val tokens: Int,
    val source: LimitSource,
    val semantics: LimitSemantics = LimitSemantics.Unknown,
)

data class ResolvedModelLimits(
    val input: ResolvedLimit,
    val output: ResolvedLimit?,
    val warnings: List<LimitWarning> = emptyList(),
) {
    val windowIsGuessed: Boolean
        get() = input.source == LimitSource.Default || input.source == LimitSource.UsageFloor

    /** True when the number came from a measurement, not from Rivet's fallback. */
    val inputIsTrusted: Boolean
        get() = !windowIsGuessed
}

/**
 * Resolves one provider+model's limits in precedence order:
 *
 *   local hard ceiling (clamp)
 *   > explicit user value
 *   > learned cap (confirmed, endpoint-scoped, unexpired)
 *   > provider-reported metadata
 *   > bundled known-model table
 *   > observed usage floor
 *   > safe default (32K)
 *
 * Manual output may be above the provider's; the request layer clamps it,
 * planning keeps the user's number and reports a warning.
 */
object ModelLimits {
    const val UNKNOWN_CONTEXT_WINDOW_TOKENS = 32_768
    const val UNKNOWN_OUTPUT_TOKENS = 8_192
    private val TIERS = intArrayOf(16_384, 32_768, 65_536, 131_072, 200_000, 262_144, 524_288, 1_048_576)

    fun resolve(
        config: ProviderConfig,
        model: String = config.model,
        table: KnownModelLimits = KnownModelLimits.bundled,
        now: Long = System.currentTimeMillis(),
    ): ResolvedModelLimits {
        val manualIn = config.manualContextWindowForModel(model)
        val manualOut = config.manualMaxOutputForModel(model)
        val provider = config.modelContextLimit?.takeIf {
            it.model == model && it.baseUrl == config.baseUrl && it.inputLimitTokens > 0
        }
        val providerOut = provider?.maxOutputTokens?.takeIf { it > 0 }
            ?: config.trustedAnthropicModelMetadata()?.takeIf { it.model == model }?.maxOutputTokens
                ?.takeIf { it > 0 }
        val learned = config.modelLimitLearning[model]?.takeIf {
            it.baseUrl == config.baseUrl && (it.expiresAt == 0L || it.expiresAt > now)
        }
        val tableHit = table.lookup(model, config.knownProviderKey())
        val warnings = linkedSetOf<LimitWarning>()

        var input: ResolvedLimit
        if (manualIn != null) {
            input = ResolvedLimit(manualIn, LimitSource.Manual)
        } else {
            val learnedIn = learned?.contextWindowTokens?.takeIf { it > 0 }
            input = when {
                learnedIn != null && provider != null && learnedIn <= provider.inputLimitTokens ->
                    ResolvedLimit(learnedIn, LimitSource.Learned)
                learnedIn != null && provider != null ->
                    ResolvedLimit(provider.inputLimitTokens, LimitSource.Provider)
                learnedIn != null -> ResolvedLimit(learnedIn, LimitSource.Learned)
                provider != null -> ResolvedLimit(
                    provider.inputLimitTokens,
                    if (config.type == ProviderType.Local) LimitSource.Local else LimitSource.Provider,
                    provider.inputLimitKind.toSemantics(),
                )
                tableHit != null -> ResolvedLimit(
                    tableHit.contextTokens,
                    LimitSource.KnownTable,
                    LimitSemantics.TotalWindow,
                )
                else -> ResolvedLimit(UNKNOWN_CONTEXT_WINDOW_TOKENS, LimitSource.Default)
            }
            if (input.source == LimitSource.Default || input.source == LimitSource.KnownTable) {
                input = withUsageFloor(input, learned?.observedInputTokens)
            }
        }

        // A local model's training context is a physical ceiling. A manual
        // value above it would make compaction and n_ctx disagree.
        val ceiling = config.localContextCeilingTokens?.takeIf { config.type == ProviderType.Local }
        if (ceiling != null && input.tokens > ceiling) {
            warnings += if (input.source == LimitSource.Manual) {
                LimitWarning.ManualAboveLocalCeiling
            } else {
                LimitWarning.LocalClamped
            }
            input = ResolvedLimit(ceiling, LimitSource.Local, LimitSemantics.TotalWindow)
        }

        var output: ResolvedLimit? = when {
            manualOut != null -> ResolvedLimit(manualOut, LimitSource.Manual)
            learned?.maxOutputTokens?.let { it > 0 } == true ->
                ResolvedLimit(learned.maxOutputTokens, LimitSource.Learned)
            providerOut != null -> ResolvedLimit(
                providerOut,
                if (config.type == ProviderType.Local) LimitSource.Local else LimitSource.Provider,
            )
            tableHit?.outputTokens?.let { it > 0 } == true ->
                ResolvedLimit(tableHit.outputTokens, LimitSource.KnownTable)
            config.type == ProviderType.Local -> {
                val local = (input.tokens / 4).coerceIn(256, 1_024)
                ResolvedLimit(local, LimitSource.Local)
            }
            else -> null
        }

        if (manualOut != null && providerOut != null && manualOut > providerOut) {
            warnings += LimitWarning.ManualAboveProvider
        }
        if (manualIn != null && learned?.contextWindowTokens?.let { it > 0 } == true) {
            warnings += LimitWarning.LearnedBelowManual
        }
        if (input.source == LimitSource.Default) warnings += LimitWarning.DefaultAssumed

        return ResolvedModelLimits(input, output, warnings.toList())
    }

    private fun withUsageFloor(input: ResolvedLimit, observed: Long?): ResolvedLimit {
        if (observed == null || observed <= 0) return input
        // A successful request proves the window is at least what the provider
        // counted. Add a small reserve for the next output, then pick the
        // smallest standard tier that fits. Never above 1M.
        val needed = observed + 4_096L
        val tier = TIERS.firstOrNull { it >= needed && it > input.tokens } ?: return input
        return ResolvedLimit(tier, LimitSource.UsageFloor, LimitSemantics.TotalWindow)
    }

    private fun LimitKind.toSemantics(): LimitSemantics = when (this) {
        LimitKind.InputOnly -> LimitSemantics.InputOnly
        LimitKind.TotalWindow -> LimitSemantics.TotalWindow
        LimitKind.Unknown -> LimitSemantics.Unknown
    }
}

/** A guarded output of the error parser, never a bare boolean. */
data class LimitEvidence(
    val contextLimitTokens: Int? = null,
    val attemptedTokens: Long? = null,
    val outputLimitTokens: Int? = null,
    val structuredCode: String? = null,
) {
    val hasNumber: Boolean
        get() = contextLimitTokens != null || outputLimitTokens != null
}

private fun lower(value: String?): String = value.orEmpty().lowercase(Locale.ROOT)

/**
 * Pure parser for provider limit errors. It only returns a number when the
 * number is explicitly a token limit; byte sizes and rate-limit text are
 * excluded. Callers must still apply the semantic guards in
 * [ModelLimitLearningPolicy].
 */
object ModelLimitEvidence {
    private val BYTES = Regex("""\b(?:bytes?|kb|mb|gb)\b""", RegexOption.IGNORE_CASE)
    private val RATE = Regex("""rate.?limit|quota|billing|per.?minute|tpm""", RegexOption.IGNORE_CASE)
    private val OPENAI_CONTEXT = Regex(
        """maximum context length is\s*([\d,]+)\s*tokens.*?(?:resulted in|you requested)\s*([\d,]+)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ANTHROPIC_CONTEXT = Regex(
        """prompt is too long:\s*([\d,]+)\s*tokens\s*>\s*([\d,]+)\s*maximum""",
        RegexOption.IGNORE_CASE,
    )
    private val GEMINI_CONTEXT = Regex(
        """input token count\s*\(\s*([\d,]+)\s*\)\s*exceeds\s*the maximum number of tokens allowed\s*\(\s*([\d,]+)\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val GEMINI_ALT = Regex(
        """input token count\s+is\s+([\d,]+)\s+but\s+model\s+only\s+supports\s+up\s+to\s+([\d,]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val GENERIC_MAX = Regex(
        """(?:maximum|max)\D{0,40}?([\d,]{3,})\s*(?:tokens|context)?""",
        RegexOption.IGNORE_CASE,
    )
    private val OUTPUT = Regex(
        """(?:max_tokens|max_completion_tokens|max_output_tokens|output tokens)\D{0,60}?([\d,]{2,})""",
        RegexOption.IGNORE_CASE,
    )
    private val OUTPUT_MAX = Regex(
        """(?:at most|maximum allowed|max)\D{0,40}?([\d,]{2,})\s*(?:completion |output )?tokens""",
        RegexOption.IGNORE_CASE,
    )
    private val OUTPUT_COMPARISON = Regex(
        """max_tokens\D{0,20}([\d,]+)\s*>\s*([\d,]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(code: Int?, body: String?, structuredCode: String? = null): LimitEvidence? {
        if (code == 429 || (code != null && code in 500..599)) return null
        val text = body.orEmpty()
        if (text.isBlank()) return structuredCode?.let { LimitEvidence(structuredCode = it) }
        if (BYTES.containsMatchIn(text) || RATE.containsMatchIn(text)) return null
        val lowerCode = lower(structuredCode)
        val isOutput = "max_tokens_exceeded" in lowerCode ||
            ("max_tokens" in lower(text) && ("too large" in lower(text) || "at most" in lower(text) ||
                "maximum" in lower(text) || ">" in text)) ||
            ("max_output_tokens" in lower(text) && "exceed" in lower(text))

        fun match(regex: Regex, attemptedFirst: Boolean): LimitEvidence? {
            val m = regex.find(text) ?: return null
            val a = m.groupValues[1].replace(",", "").toIntOrNull()
            val b = m.groupValues[2].replace(",", "").toIntOrNull()
            if (a == null || b == null) return null
            val attempted = if (attemptedFirst) a else b
            val limit = if (attemptedFirst) b else a
            return LimitEvidence(contextLimitTokens = limit, attemptedTokens = attempted.toLong())
        }

        if (isOutput) {
            // Both numbers matter: "max_tokens is too large: 64000 ... at most
            // 16384" names the attempted value first and the real limit second.
            // The real limit is the smaller one.
            val numbers = listOfNotNull(
                OUTPUT.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull(),
                OUTPUT_MAX.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull(),
                OUTPUT_COMPARISON.find(text)?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull(),
            )
            return LimitEvidence(
                outputLimitTokens = numbers.minOrNull(),
                structuredCode = structuredCode,
            )
        }
        match(OPENAI_CONTEXT, attemptedFirst = false)?.let { return it }
        match(ANTHROPIC_CONTEXT, attemptedFirst = true)?.let { return it }
        match(GEMINI_CONTEXT, attemptedFirst = true)?.let { return it }
        match(GEMINI_ALT, attemptedFirst = true)?.let { return it }
        // Last resort: a message that says "context ... 128000" with one
        // number. Only accepted when the text names a context limit; byte
        // sizes were already rejected above.
        if ("context" in lower(text) || "prompt" in lower(text)) {
            val limit = GENERIC_MAX.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (limit != null) {
                return LimitEvidence(contextLimitTokens = limit, structuredCode = structuredCode)
            }
        }
        return structuredCode?.let { LimitEvidence(structuredCode = it) }
    }
}
