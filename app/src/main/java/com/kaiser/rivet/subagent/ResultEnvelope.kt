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
        }.toString()

        val initial = render(parsed)
        if (initial.toByteArray(Charsets.UTF_8).size <= maxTotalBytes) {
            return initial
        }

        // First pass: proportionally truncate verified.diff across envelopes that carry a diff.
        val strippedDiffs = parsed.map { replaceDiff(it, "", markTruncated = hasNonEmptyDiff(it)) }
        val baseBytes = render(strippedDiffs).toByteArray(Charsets.UTF_8).size
        val availableDiffBytes = (maxTotalBytes - baseBytes - (parsed.size * 16)).coerceAtLeast(0)
        val diffByteSizes = parsed.map { diffText(it).toByteArray(Charsets.UTF_8).size }
        val totalDiffBytes = diffByteSizes.sum()

        val diffCapped = if (totalDiffBytes > 0 && availableDiffBytes > 0) {
            parsed.mapIndexed { idx, obj ->
                val rawDiff = diffText(obj)
                val currentBytes = diffByteSizes[idx]
                if (currentBytes == 0) {
                    obj
                } else {
                    val share = ((availableDiffBytes.toLong() * currentBytes.toLong()) / totalDiffBytes.toLong()).toInt()
                    val cut = truncateUtf8ByLines(rawDiff, share)
                    replaceDiff(obj, cut, markTruncated = cut.length < rawDiff.length)
                }
            }
        } else {
            strippedDiffs
        }

        val afterDiffCap = render(diffCapped)
        if (afterDiffCap.toByteArray(Charsets.UTF_8).size <= maxTotalBytes) {
            return afterDiffCap
        }

        // Second pass (fallback if non-diff fields alone exceed maxTotalBytes): trim claimed findings/summary.
        val perWorkerClaimBudget = ((maxTotalBytes / parsed.size.coerceAtLeast(1)) - 256).coerceAtLeast(64)
        val fullyCapped = strippedDiffs.map { trimClaimed(it, perWorkerClaimBudget) }
        return render(fullyCapped)
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

    private fun truncateUtf8ByLines(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val builder = StringBuilder()
        var used = 0
        for (line in text.split('\n')) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (used + lineBytes > maxBytes) break
            builder.append(line).append('\n')
            used += lineBytes
        }
        if (builder.isNotEmpty()) return builder.toString()
        return truncateUtf8(text, maxBytes)
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
