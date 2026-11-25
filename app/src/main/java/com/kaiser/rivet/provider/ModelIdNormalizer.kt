package com.kaiser.rivet.provider

import java.util.Locale

/**
 * Model ids are not stable identifiers. The same model arrives as
 * `openai/gpt-4o`, `gpt-4o-2024-08-06`, `gpt-4o:free`, or
 * `accounts/fireworks/models/gpt-4o`. Normalisation is deliberately narrow:
 * it removes routing/date decoration, never the words that distinguish a
 * model (`mini`, `flash`, `pro`, `-32k`, a version digit).
 */
internal object ModelIdNormalizer {

    private val ROUTE_SUFFIXES = setOf(
        "free", "beta", "extended", "thinking", "online", "nitro", "floor", "batch",
    )
    private val DATE_SUFFIX = Regex("""[-_](?:19|20)\d{2}[-_]?\d{2}[-_]?\d{2}$""")
    private val COMPACT_DATE = Regex("""[-_]v?(?:19|20)\d{6}$""")
    private val QUANT_SUFFIX = Regex(
        """-(?:q[0-9]+(?:_[a-z0-9]+)*|iq[0-9]+_[a-z0-9]+|f16|bf16|fp16|fp32)$""",
        RegexOption.IGNORE_CASE,
    )
    private val KNOWN_OWNERS = setOf(
        "openai", "anthropic", "google", "deepseek", "meta-llama", "meta", "qwen",
        "mistralai", "mistral", "x-ai", "moonshotai", "moonshot", "z-ai", "zai",
        "cohere", "nvidia", "amazon", "microsoft", "ibm-granite", "ibm", "ai21",
        "perplexity", "togethercomputer", "fireworks", "cerebras", "inclusionai",
        "baidu", "bytedance", "upstage", "liquid", "aion-labs", "arcee-ai",
    )

    /** Normalised comparison key. Never used as a display value. */
    fun normalize(raw: String, local: Boolean = false): String {
        var id = raw.trim().lowercase(Locale.ROOT)
        if (id.isEmpty()) return ""
        if (local) {
            id = id.removeSuffix(".gguf")
            id = QUANT_SUFFIX.replace(id, "")
        }
        // OpenRouter and similar routers put a route after the last colon.
        val colon = id.lastIndexOf(':')
        if (colon > 0 && id.substring(colon + 1) in ROUTE_SUFFIXES) id = id.substring(0, colon)
        if (id.endsWith("-free") && id.length > "-free".length + 2) {
            id = id.removeSuffix("-free")
        }
        id = stripFireworksPath(id)
        id = stripOneOwner(id)
        id = DATE_SUFFIX.replace(id, "")
        id = COMPACT_DATE.replace(id, "")
        // Dots and underscores are version separators, not identity: `gpt-4.1`
        // and `gpt-4-1` are the same family. Keep this after date stripping so
        // `2024-08-06` is gone before it becomes `2024-08-06`.
        return id.replace('.', '-').replace('_', '-').trim('-')
    }

    private fun stripFireworksPath(id: String): String {
        val prefix = "accounts/fireworks/models/"
        return if (id.startsWith(prefix) && id.length > prefix.length) id.removePrefix(prefix) else id
    }

    private fun stripOneOwner(id: String): String {
        val slash = id.indexOf('/')
        if (slash <= 0 || slash == id.length - 1) return id
        val owner = id.substring(0, slash)
        // Unknown owners are still stripped once: an aggregator can invent its
        // own namespace, and the model part is the only useful half. The
        // canonical-table lookup rejects ambiguity, so this is not a guess.
        return id.substring(slash + 1)
    }

    /**
     * Pick the listed model whose id normalises to the typed id. Exact
     * case-insensitive ids win. Among normalised matches, two different ids
     * with different limits are ambiguous and must not be chosen.
     */
    fun matchListed(model: String, listed: List<ModelInfo>): ModelInfo? {
        if (model.isBlank()) return null
        listed.firstOrNull { it.id.equals(model, ignoreCase = true) }?.let { return it }
        val key = normalize(model)
        if (key.isEmpty()) return null
        val matches = listed.filter { normalize(it.id) == key }
        if (matches.isEmpty()) return null
        val first = matches.first()
        val sameLimits = matches.all {
            it.inputLimitTokens == first.inputLimitTokens &&
                it.outputLimitTokens == first.outputLimitTokens
        }
        return first.takeIf { sameLimits }
    }
}
