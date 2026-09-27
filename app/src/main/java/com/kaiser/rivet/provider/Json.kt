package com.kaiser.rivet.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// Safe navigational casts for tolerant parsing: providers add fields, omit
// fields, and occasionally send unexpected types; every accessor yields
// null instead of throwing on an unknown shape.
internal fun JsonElement.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement.arr(): JsonArray? = this as? JsonArray

internal fun JsonElement.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.positiveInt(): Int? =
    (this as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

internal fun jsonNestingWithinLimit(raw: String, limit: Int = 64): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in raw) {
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > limit) return false
                '}', ']' -> depth--
            }
        }
    }
    return true
}

// A 200 with a non-JSON body is a broken server, not an empty model list;
// callers surface InvalidResponse rather than an unhandled decode crash.
internal fun parseJsonObject(text: String): JsonObject? = try {
    if (!jsonNestingWithinLimit(text)) null else Json.parseToJsonElement(text) as? JsonObject
} catch (e: IllegalArgumentException) {
    null
}
