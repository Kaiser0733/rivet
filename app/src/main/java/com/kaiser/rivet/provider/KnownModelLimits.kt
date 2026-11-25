package com.kaiser.rivet.provider

import java.util.Locale

data class KnownModelLimit(val contextTokens: Int, val outputTokens: Int?)

/**
 * Bundled, offline known-model data. The resource is generated from
 * models.dev by scripts/generate_model_limits.py and shipped with the APK.
 *
 * Rows are `provider<TAB>model<TAB>context<TAB>output`; `*` is the canonical
 * row. Canonical rows use the smallest context/output seen across providers,
 * so an unknown gateway is never planned above the smallest known route.
 */
interface KnownModelLimits {
    fun lookup(model: String, providerKey: String?): KnownModelLimit?

    companion object {
        val bundled: KnownModelLimits by lazy { BundledModelLimits.load() }
        val empty: KnownModelLimits = object : KnownModelLimits {
            override fun lookup(model: String, providerKey: String?): KnownModelLimit? = null
        }
    }
}

internal class BundledModelLimits private constructor(
    private val rows: Map<String, Map<String, KnownModelLimit>>,
    private val ambiguous: Set<String>,
) : KnownModelLimits {

    override fun lookup(model: String, providerKey: String?): KnownModelLimit? {
        val exactKey = model.trim().lowercase(Locale.ROOT)
        val providerRows = providerKey?.let { rows[it] }
        providerRows?.get(exactKey)?.let { return it }

        val normalized = ModelIdNormalizer.normalize(model)
        if (normalized.isEmpty()) return null
        // Provider-scoped matching comes before the canonical row: an
        // OpenRouter route can have a smaller window than the base model.
        providerRows?.get(normalized)?.let { return it }
        providerRows?.entries?.firstOrNull { ModelIdNormalizer.normalize(it.key) == normalized }
            ?.value?.let { return it }

        rows["*"]?.get(exactKey)?.let { return it }
        // A typed `openai/gpt-4o` normalises to `gpt-4o`, which is an exact
        // canonical key; try it before the normalised index. The index is
        // ambiguous when several dated snapshots share a stem.
        rows["*"]?.get(normalized)?.let { return it }
        if (normalized in ambiguous) return null
        rows["*"]?.entries?.firstOrNull { ModelIdNormalizer.normalize(it.key) == normalized }
            ?.value?.let { return it }
        return null
    }

    companion object {
        private const val RESOURCE = "/model-limits.tsv"

        fun load(): KnownModelLimits = runCatching {
            val stream = BundledModelLimits::class.java.getResourceAsStream(RESOURCE)
                ?: return@runCatching KnownModelLimits.empty
            parse(stream.bufferedReader(Charsets.UTF_8).readLines())
        }.getOrDefault(KnownModelLimits.empty)

        internal fun parse(lines: List<String>): KnownModelLimits {
            val rows = linkedMapOf<String, MutableMap<String, KnownModelLimit>>()
            val normalizedValues = mutableMapOf<String, MutableSet<Pair<Int, Int?>>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size < 4) continue
                val provider = parts[0].trim().lowercase(Locale.ROOT)
                val model = parts[1].trim().lowercase(Locale.ROOT)
                if (provider.isEmpty() || model.isEmpty()) continue
                val context = parts[2].trim().toIntOrNull()?.takeIf { it > 0 } ?: continue
                val output = parts[3].trim().toIntOrNull()?.takeIf { it > 0 }
                rows.getOrPut(provider) { linkedMapOf() }[model] = KnownModelLimit(context, output)
                if (provider == "*") {
                    val normalized = ModelIdNormalizer.normalize(model)
                    if (normalized.isNotEmpty()) {
                        normalizedValues.getOrPut(normalized) { linkedSetOf() }
                            .add(context to output)
                    }
                }
            }
            // A normalised key that maps to more than one canonical id is not a
            // match; the model is genuinely ambiguous (for example two dated
            // snapshots with different windows).
            val ambiguous = normalizedValues.filterValues { it.size > 1 }.keys
            return BundledModelLimits(rows, ambiguous)
        }
    }
}

/** Which provider key the bundled table should be consulted under. */
internal fun ProviderConfig.knownProviderKey(): String? = when (type) {
    ProviderType.OpenAi, ProviderType.ChatGptPlan -> "openai"
    ProviderType.Anthropic -> "anthropic"
    ProviderType.Gemini -> "google"
    ProviderType.OpenRouter -> "openrouter"
    ProviderType.Local -> null
    ProviderType.OpenAiCompatible -> hostProviderKey(baseUrl)
}

private fun hostProviderKey(baseUrl: String): String? {
    val host = runCatching {
        java.net.URI(baseUrl.trim()).host?.lowercase(Locale.ROOT)
    }.getOrNull() ?: return null
    return when {
        host == "api.openai.com" -> "openai"
        host == "api.anthropic.com" -> "anthropic"
        host == "generativelanguage.googleapis.com" -> "google"
        host.endsWith("openrouter.ai") -> "openrouter"
        host == "api.deepseek.com" -> "deepseek"
        host == "api.mistral.ai" -> "mistral"
        host == "api.groq.com" -> "groq"
        host == "api.together.xyz" -> "togetherai"
        host == "api.fireworks.ai" -> "fireworks-ai"
        host == "api.cerebras.ai" -> "cerebras"
        host == "api.x.ai" -> "xai"
        host.endsWith("moonshot.ai") || host.endsWith("moonshot.cn") -> "moonshotai"
        host == "open.bigmodel.cn" || host == "api.z.ai" -> "zai"
        host == "api.cohere.com" -> "cohere"
        host == "api.perplexity.ai" -> "perplexity"
        host == "api.ai21.com" -> "ai21"
        host == "integrate.api.nvidia.com" -> "nvidia"
        else -> null
    }
}
