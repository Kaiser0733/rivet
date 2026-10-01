package com.kaiser.rivet.storage

/** Only counts are exported; provider prompts, keys, headers and project data never enter this text. */
fun SessionUsage.diagnostics(): String = buildString {
    fun count(label: String, value: Long?) { append(label).append(": ").append(value?.toString() ?: "Not reported").append('\n') }
    append("Provider-reported counts; unreported values are omitted from totals.\n")
    append("Input categories differ by provider. Cached input may already be included in raw input; these values are not added together.\n\n")
    append("All requests (including compaction): ").append(reportedRequests + unknownRequests).append('\n')
    append("Requests without reported context input: ").append(unknownRequests).append('\n')
    count("Raw input", reportedInputTokens)
    count("Output", reportedOutputTokens)
    count("Cache read", cacheReadTokens)
    count("Cache creation/write", cacheCreationTokens)
    count("Reasoning", reasoningTokens)
    count("Context input", contextInputTokens)
    count("Total", totalTokens)
    append("\nCompaction requests: ").append(compactionRequests).append('\n')
    append("Compaction requests without reported context input: ").append(compactionUnknownRequests).append('\n')
    count("Compaction input", compaction?.inputTokens)
    count("Compaction output", compaction?.outputTokens)
    count("Compaction cache read", compaction?.cacheReadTokens)
    count("Compaction cache creation/write", compaction?.cacheCreationTokens)
    append("\nLatest request: ").append(if (latestIsCompaction) "Compaction" else "Conversation").append('\n')
    count("Latest input", latestUsage?.inputTokens)
    count("Latest output", latestUsage?.outputTokens)
    count("Latest cache read", latestUsage?.cacheReadTokens)
    count("Latest cache creation/write", latestUsage?.cacheCreationTokens)
    count("Latest reasoning", latestUsage?.reasoningTokens)
}
