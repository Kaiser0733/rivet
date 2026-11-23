package com.kaiser.rivet.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ReadFileArguments(val path: String, val offset: Long)

internal fun readFileArguments(raw: String): ReadFileArguments {
    val args = Json.parseToJsonElement(raw) as? JsonObject
    require(args != null && args.keys.all { it == "path" || it == "offset" })
    val path = args["path"] as? JsonPrimitive
    require(path != null && path.isString)
    val offset = args["offset"]?.let { value ->
        require(value is JsonPrimitive && !value.isString)
        // JSON Schema integers include integral decimals and exponent notation.
        try {
            value.content.toBigDecimal().longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Read offset must be an integer in Long range.")
        }
    } ?: 0L
    require(offset >= 0)
    return ReadFileArguments(path.content, offset)
}
