package com.kaiser.rivet.provider

/**
 * Turns provider evidence into a stored cap. Every rule here exists to stop a
 * flaky or unrelated 400 from shrinking a correct window:
 *
 *  - only a numeric limit is stored (a bare "context overflow" never changes a value);
 *  - the error must be self-consistent (attempted tokens >= limit, both sane);
 *  - the first sighting is provisional (24 h), a second consistent one is
 *    confirmed (90 days);
 *  - only a successful request that proves the cap too low clears it;
 *  - manual values are never overwritten.
 */
object ModelLimitLearningPolicy {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_CONTEXT = 512
    private const val MAX_CONTEXT = 10_000_000
    private const val MIN_OUTPUT = 64
    private const val MAX_OUTPUT = 1_000_000

    data class Result(val learning: ModelLimitLearning, val promptCap: Int?)

    fun observeContext(
        config: ProviderConfig,
        model: String,
        error: ProviderError.ContextOverflow,
        estimatedInputTokens: Long,
        now: Long = System.currentTimeMillis(),
    ): Result? {
        val limit = error.reportedLimitTokens ?: return null
        if (limit !in MIN_CONTEXT..MAX_CONTEXT) return null
        val attempted = error.attemptedTokens
        if (attempted != null) {
            if (attempted < limit || attempted > limit.toLong() * 4) return null
            if (estimatedInputTokens > 0 &&
                (attempted < estimatedInputTokens / 2 || attempted > estimatedInputTokens * 4)) {
                return null
            }
        }
        // Compare against what we would believe without this record, otherwise
        // the second identical error would look like "not lower" and never
        // confirm the first.
        val current = config.copy(modelLimitLearning = emptyMap()).resolveLimits(model, now = now)
        if (current.input.source == LimitSource.Manual) return null
        if (limit >= current.input.tokens) return null
        val existing = config.modelLimitLearning[model]
        val same = existing?.contextWindowTokens?.let {
            kotlin.math.abs(it - limit) <= maxOf(1_024, limit / 100)
        } == true
        val confirmations = if (same) (existing?.confirmations ?: 1) + 1 else 1
        val ttl = if (confirmations >= 2) 90 * DAY_MS else DAY_MS
        val reserve = current.output?.tokens ?: minOf(16_384, maxOf(2_048, limit / 4))
        val promptCap = (limit - reserve).coerceAtLeast(512)
        val learning = ModelLimitLearning(
            baseUrl = config.baseUrl,
            contextWindowTokens = limit,
            maxOutputTokens = existing?.maxOutputTokens,
            attemptedTokens = attempted,
            observedInputTokens = existing?.observedInputTokens,
            confirmations = confirmations,
            firstSeenAt = existing?.firstSeenAt ?: now,
            lastSeenAt = now,
            expiresAt = now + ttl,
        )
        return Result(learning, promptCap)
    }

    fun observeOutput(
        config: ProviderConfig,
        model: String,
        error: ProviderError.OutputLimitExceeded,
        now: Long = System.currentTimeMillis(),
    ): Result? {
        val limit = error.reportedLimitTokens ?: return null
        if (limit !in MIN_OUTPUT..MAX_OUTPUT) return null
        val current = config.copy(modelLimitLearning = emptyMap()).resolveLimits(model, now = now)
        if (current.output?.source == LimitSource.Manual) return null
        if (current.output?.tokens?.let { limit >= it } == true) return null
        val existing = config.modelLimitLearning[model]
        val learning = ModelLimitLearning(
            baseUrl = config.baseUrl,
            contextWindowTokens = existing?.contextWindowTokens,
            maxOutputTokens = limit,
            attemptedTokens = error.attemptedTokens,
            observedInputTokens = existing?.observedInputTokens,
            confirmations = (existing?.confirmations ?: 1).coerceAtLeast(1),
            firstSeenAt = existing?.firstSeenAt ?: now,
            lastSeenAt = now,
            expiresAt = now + 90 * DAY_MS,
        )
        return Result(learning, null)
    }

    /**
     * A successful request that reported more input tokens than the assumed
     * window proves the assumption was too small. This is the only upward
     * correction; it is recorded as a lower bound, not an exact window.
     */
    fun observeUsage(
        config: ProviderConfig,
        model: String,
        reportedInputTokens: Long,
        now: Long = System.currentTimeMillis(),
    ): ModelLimitLearning? {
        if (reportedInputTokens <= 0) return null
        val current = config.resolveLimits(model, now = now)
        val existing = config.modelLimitLearning[model]
        // A learned cap that a successful request just exceeded was too low.
        if (current.input.source == LimitSource.Learned &&
            existing?.contextWindowTokens != null &&
            reportedInputTokens > existing.contextWindowTokens + 1_024
        ) {
            return ModelLimitLearning(
                baseUrl = config.baseUrl,
                contextWindowTokens = null,
                maxOutputTokens = existing.maxOutputTokens,
                attemptedTokens = existing.attemptedTokens,
                observedInputTokens = maxOf(reportedInputTokens, existing.observedInputTokens ?: 0),
                confirmations = existing.confirmations,
                firstSeenAt = existing.firstSeenAt,
                lastSeenAt = now,
                expiresAt = now + 30 * DAY_MS,
            )
        }
        if (current.input.source != LimitSource.Default && current.input.source != LimitSource.KnownTable) {
            return null
        }
        if (reportedInputTokens <= current.input.tokens) return null
        val observed = maxOf(reportedInputTokens, existing?.observedInputTokens ?: 0)
        // A request this large succeeded, so a learned context cap below it was
        // wrong. Clear it; the observed floor replaces it.
        val learnedContext = existing?.contextWindowTokens?.takeIf {
            reportedInputTokens <= it + 1_024
        }
        return ModelLimitLearning(
            baseUrl = config.baseUrl,
            contextWindowTokens = learnedContext,
            maxOutputTokens = existing?.maxOutputTokens,
            attemptedTokens = existing?.attemptedTokens,
            observedInputTokens = observed,
            confirmations = existing?.confirmations ?: 1,
            firstSeenAt = existing?.firstSeenAt ?: now,
            lastSeenAt = now,
            expiresAt = now + 30 * DAY_MS,
        )
    }
}
