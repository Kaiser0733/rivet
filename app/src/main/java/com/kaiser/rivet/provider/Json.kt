package com.kaiser.rivet.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Safe navigational casts for tolerant parsing: providers add fields, omit
// fields, and occasionally send unexpected types; every accessor yields
// null instead of throwing on an unknown shape.
internal fun JsonElement.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement.arr(): JsonArray? = this as? JsonArray

internal fun JsonElement.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
