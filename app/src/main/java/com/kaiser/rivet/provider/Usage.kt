package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentUsage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

private fun JsonElement?.count(): Long? =
    (this as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

internal fun openAiUsage(root: JsonObject): AgentUsage? {
    val usage = root["usage"]?.obj() ?: return null
    val input = usage["prompt_tokens"].count()
    val output = usage["completion_tokens"].count()
    val total = usage["total_tokens"].count()
    if (input == null && output == null && total == null) return null
    return AgentUsage(input, output,
        usage["prompt_tokens_details"]?.obj()?.get("cached_tokens").count(),
        usage["completion_tokens_details"]?.obj()?.get("reasoning_tokens").count(), total)
}

internal fun anthropicUsage(root: JsonObject): AgentUsage? {
    val usage = root["usage"]?.obj() ?: return null
    val input = usage["input_tokens"].count()
    val output = usage["output_tokens"].count()
    val cache = usage["cache_read_input_tokens"].count()
    if (input == null && output == null && cache == null) return null
    return AgentUsage(input, output, cache)
}

internal fun geminiUsage(root: JsonObject): AgentUsage? {
    val usage = root["usageMetadata"]?.obj() ?: return null
    val input = usage["promptTokenCount"].count()
    val output = usage["candidatesTokenCount"].count()
    val total = usage["totalTokenCount"].count()
    if (input == null && output == null && total == null) return null
    return AgentUsage(input, output, usage["cachedContentTokenCount"].count(),
        usage["thoughtsTokenCount"].count(), total)
}
