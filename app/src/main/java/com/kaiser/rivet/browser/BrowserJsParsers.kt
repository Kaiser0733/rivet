package com.kaiser.rivet.browser

import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class BrowserClickTarget(
    val found: Boolean,
    val risky: Boolean,
    val description: String,
    val inForm: Boolean = false,
    val tag: String = "",
)

internal object BrowserJsParsers {
    private val RISKY_LABEL_REGEX = Regex("(?i)submit|send|publish|pay|buy|post|delete|confirm")

    fun parseClickTarget(raw: String): BrowserClickTarget {
        val clean = BrowserElementRefs.unwrapJsResult(raw)
        val obj = runCatching { Json.parseToJsonElement(clean) as? JsonObject }.getOrNull()
        if (obj != null) {
            val found = (obj["found"] as? JsonPrimitive)?.booleanOrNull == true
            if (!found) {
                return BrowserClickTarget(found = false, risky = false, description = "page control")
            }
            val description = (obj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.ifBlank { null } ?: "page control"
            val risky = ((obj["risky"] as? JsonPrimitive)?.booleanOrNull ?: false) ||
                RISKY_LABEL_REGEX.containsMatchIn(description)
            val inForm = (obj["inForm"] as? JsonPrimitive)?.booleanOrNull ?: false
            val tag = (obj["tag"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return BrowserClickTarget(found = true, risky = risky, description = description, inForm = inForm, tag = tag)
        }
        return BrowserClickTarget(found = false, risky = false, description = "page control")
    }

    fun parseScrollYValue(raw: String): Int? =
        raw.trim().trim('"').toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()

    fun parseSnapshotScrollY(raw: String): Int {
        val clean = BrowserElementRefs.unwrapJsResult(raw)
        val obj = runCatching { Json.parseToJsonElement(clean) as? JsonObject }.getOrNull()
            ?: return 0
        return (obj["scrollY"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.roundToInt() ?: 0
    }
}
