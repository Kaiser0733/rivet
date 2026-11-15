package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.RunPhase
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class FileChangeKind(val wire: String) {
    Added("added"),
    Modified("modified"),
    Deleted("deleted"),
}

data class ChangedFile(
    val path: String,
    val kind: FileChangeKind,
    val additions: Int = 0,
    val deletions: Int = 0,
)

data class ObservedCommand(
    val command: String,
    val exitCode: Int?,
    val sync: String? = null,
)

/**
 * The runtime-assembled result. Ground truth lives under `verified`; the
 * worker's prose lives under `claimed` and can never overwrite it.
 */
object ResultEnvelope {
    const val MAX_DIFF_BYTES = WorkerDiff.MAX_BYTES
    const val MAX_AWAIT_BYTES = 24 * 1024
    const val MAX_COMMAND_CHARS = 120

    fun build(
        workerId: String,
        state: RunPhase,
        claim: WorkerClaim,
        stopReason: String? = null,
        error: String? = null,
        files: List<ChangedFile> = emptyList(),
        outOfScopeChanges: List<String> = emptyList(),
        commands: List<ObservedCommand> = emptyList(),
        diff: WorkerDiff? = null,
    ): String {
        val claimedSummary = if (state == RunPhase.Failed && error != null) error
        else claim.summary
        val verified = buildJsonObject {
            put("state", state.name.lowercase(Locale.ROOT))
            put("stop_reason", stopReason?.let { JsonPrimitive(it) } ?: JsonNull)
            put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
            put("files_changed", buildJsonArray {
                files.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("kind", file.kind.wire)
                        put("+", file.additions)
                        put("-", file.deletions)
                    })
                }
            })
            put("out_of_scope_changes", buildJsonArray {
                outOfScopeChanges.forEach { add(JsonPrimitive(it)) }
            })
            put("commands", buildJsonArray {
                commands.forEach { command ->
                    add(buildJsonObject {
                        put("command", command.command.take(MAX_COMMAND_CHARS))
                        put("exit_code", command.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("sync", command.sync?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
            put("diff", diff?.text.orEmpty())
            put("truncated", diff?.truncated == true)
        }
        val claimed = buildJsonObject {
            put("summary", claimedSummary)
            put("findings", buildJsonArray {
                claim.findings.forEach { add(JsonPrimitive(it)) }
            })
            put("open_questions", buildJsonArray {
                claim.openQuestions.forEach { add(JsonPrimitive(it)) }
            })
            put("truncated", claim.truncated)
        }
        return buildJsonObject {
            put("worker_id", workerId)
            put("verified", verified)
            put("claimed", claimed)
        }.toString()
    }

    /** Convenience for callers that need the parsed JSON without a second codec. */
    fun fromJson(text: String) = Json.parseToJsonElement(text)

    /**
     * Assembles the coordinator's `await_subagents` tool result across returned worker
     * envelopes and guarantees the total UTF-8 JSON size is at most [maxTotalBytes]
     * (24 KB by default). When the combined payload exceeds the cap, per-worker
     * `verified.diff` fields are truncated proportionally first (setting
     * `verified.truncated = true`) so the JSON structure is never lost.
     */
    fun assembleAwaitResult(
        envelopes: List<String>,
        timedOut: List<String> = emptyList(),
        stillRunning: List<String> = emptyList(),
        unknownWorkers: List<String> = emptyList(),
        maxTotalBytes: Int = MAX_AWAIT_BYTES,
    ): String {
        val parsed = envelopes.mapNotNull { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        }
        fun render(items: List<JsonObject>): String = buildJsonObject {
            put("workers", buildJsonArray { items.forEach { add(it) } })
            if (timedOut.isNotEmpty()) {
                put("timed_out", buildJsonArray { timedOut.forEach { add(JsonPrimitive(it)) } })
            }
            if (stillRunning.isNotEmpty()) {
                put("running", buildJsonArray { stillRunning.forEach { add(JsonPrimitive(it)) } })
            }
            if (unknownWorkers.isNotEmpty()) {
                put("unknown_workers", buildJsonArray { unknownWorkers.forEach { add(JsonPrimitive(it)) } })
            }
        }.toString()

        val initial = render(parsed)
        if (initial.toByteArray(Charsets.UTF_8).size <= maxTotalBytes) {
            return initial
        }

        // First pass: proportionally truncate verified.diff across envelopes that carry a diff,
        // budgeting by JSON-encoded string bytes so escaped newlines/quotes cannot overshoot maxTotalBytes.
        val strippedDiffs = parsed.map { replaceDiff(it, "", markTruncated = hasNonEmptyDiff(it)) }
        val baseBytes = render(strippedDiffs).toByteArray(Charsets.UTF_8).size
        // Reserve 1 byte per worker in case a smaller diff stays untruncated ("false" vs "true").
        val availableDiffBytes = (maxTotalBytes - baseBytes - parsed.size).coerceAtLeast(0)
        val diffEncodedSizes = parsed.map { jsonEncodedContentBytes(diffText(it)) }
        val totalDiffBytes = diffEncodedSizes.sum()

        fun capDiffs(items: List<JsonObject>, budgetBytes: Int): List<JsonObject> {
            if (totalDiffBytes <= 0 || budgetBytes <= 0) {
                return items.map { replaceDiff(it, "", markTruncated = hasNonEmptyDiff(it)) }
            }
            return items.mapIndexed { idx, obj ->
                val rawDiff = diffText(obj)
                val currentBytes = diffEncodedSizes[idx]
                if (currentBytes == 0) {
                    obj
                } else {
                    val share = ((budgetBytes.toLong() * currentBytes.toLong()) / totalDiffBytes.toLong()).toInt()
                    val cut = truncateJsonEncodedByLines(rawDiff, share)
                    replaceDiff(obj, cut, markTruncated = cut.length < rawDiff.length)
                }
            }
        }

        val diffCapped = capDiffs(parsed, availableDiffBytes)
        val afterDiffCap = render(diffCapped)
        if (afterDiffCap.toByteArray(Charsets.UTF_8).size <= maxTotalBytes) {
            return afterDiffCap
        }

        // Second pass (fallback if non-diff fields alone exceed maxTotalBytes): trim claimed findings/summary,
        // then fit truncated diffs into whatever budget remains.
        val perWorkerClaimBudget = ((maxTotalBytes / parsed.size.coerceAtLeast(1)) - 256).coerceAtLeast(64)
        val trimmedClaims = parsed.map { trimClaimed(it, perWorkerClaimBudget) }
        val strippedAfterClaimTrim = trimmedClaims.map { replaceDiff(it, "", markTruncated = hasNonEmptyDiff(it)) }
        val claimTrimBaseBytes = render(strippedAfterClaimTrim).toByteArray(Charsets.UTF_8).size
        val remainingDiffBudget = (maxTotalBytes - claimTrimBaseBytes - parsed.size).coerceAtLeast(0)
        return render(capDiffs(trimmedClaims, remainingDiffBudget))
    }

    private fun diffText(envelope: JsonObject): String =
        envelope["verified"]?.jsonObject?.get("diff")?.jsonPrimitive?.content.orEmpty()

    private fun hasNonEmptyDiff(envelope: JsonObject): Boolean = diffText(envelope).isNotEmpty()

    private fun replaceDiff(envelope: JsonObject, newDiff: String, markTruncated: Boolean): JsonObject {
        val verified = envelope["verified"]?.jsonObject ?: return envelope
        val prevTruncated = verified["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val updatedVerified = buildJsonObject {
            for ((k, v) in verified) {
                when (k) {
                    "diff" -> put("diff", newDiff)
                    "truncated" -> put("truncated", prevTruncated || markTruncated)
                    else -> put(k, v)
                }
            }
        }
        return buildJsonObject {
            for ((k, v) in envelope) {
                if (k == "verified") put("verified", updatedVerified) else put(k, v)
            }
        }
    }

    private fun trimClaimed(envelope: JsonObject, budgetBytes: Int): JsonObject {
        val claimed = envelope["claimed"]?.jsonObject ?: return envelope
        val rawSummary = claimed["summary"]?.jsonPrimitive?.content.orEmpty()
        val cutSummary = truncateUtf8(rawSummary, budgetBytes)
        val updatedClaimed = buildJsonObject {
            put("summary", cutSummary)
            put("findings", buildJsonArray {})
            put("open_questions", buildJsonArray {})
            put("truncated", true)
        }
        return buildJsonObject {
            for ((k, v) in envelope) {
                if (k == "claimed") put("claimed", updatedClaimed) else put(k, v)
            }
        }
    }

    private fun jsonEncodedContentBytes(text: String): Int =
        (JsonPrimitive(text).toString().toByteArray(Charsets.UTF_8).size - 2).coerceAtLeast(0)

    private fun truncateJsonEncodedByLines(text: String, maxEncodedBytes: Int): String {
        if (maxEncodedBytes <= 0 || text.isEmpty()) return ""
        if (jsonEncodedContentBytes(text) <= maxEncodedBytes) return text
        val lines = text.split('\n')
        val builder = StringBuilder()
        var usedEncoded = 0
        for ((index, line) in lines.withIndex()) {
            val lineEncoded = jsonEncodedContentBytes(line)
            val sepEncoded = if (index < lines.lastIndex) 2 else 0 // JSON-escaped "\\n"
            if (usedEncoded + lineEncoded + sepEncoded > maxEncodedBytes) break
            builder.append(line)
            if (index < lines.lastIndex) builder.append('\n')
            usedEncoded += lineEncoded + sepEncoded
        }
        if (builder.isNotEmpty()) return builder.toString()
        return truncateJsonEncodedPrefix(text, maxEncodedBytes)
    }

    private fun truncateJsonEncodedPrefix(text: String, maxEncodedBytes: Int): String {
        if (maxEncodedBytes <= 0 || text.isEmpty()) return ""
        if (jsonEncodedContentBytes(text) <= maxEncodedBytes) return text
        // Shrink by character index (backing off surrogate pairs) so JSON escaping stays within budget.
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            val safeEnd = if (mid < text.length && Character.isLowSurrogate(text[mid])) mid - 1 else mid
            val candidate = text.substring(0, safeEnd.coerceAtLeast(0))
            if (jsonEncodedContentBytes(candidate) <= maxEncodedBytes) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        val finalEnd = if (low < text.length && Character.isLowSurrogate(text[low])) (low - 1).coerceAtLeast(0) else low
        return text.substring(0, finalEnd)
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0b1100_0000) == 0b1000_0000) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
