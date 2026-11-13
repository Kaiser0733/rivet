package com.kaiser.rivet.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A stable short handle for a page control, so a read does not need a second round trip. */
data class BrowserElementRef(val ref: Int, val label: String, val tag: String = "") {
    fun token(): String = "#$ref"
}

object BrowserElementRefs {
    fun unwrapJsResult(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return ""
        val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
        return when (parsed) {
            is JsonPrimitive -> if (parsed.isString) parsed.content else trimmed
            else -> trimmed
        }
    }

    fun fromJson(raw: String): List<BrowserElementRef> {
        val clean = unwrapJsResult(raw)
        if (clean.isEmpty()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(clean) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val labelPrim = obj["label"] as? JsonPrimitive ?: return@mapIndexedNotNull null
            if (!labelPrim.isString) return@mapIndexedNotNull null
            val label = labelPrim.content.take(80)
            val tag = (obj["tag"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            BrowserElementRef(index + 1, label, tag)
        }
    }
}
