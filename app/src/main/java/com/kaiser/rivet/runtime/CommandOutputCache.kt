package com.kaiser.rivet.runtime

import java.io.File
import java.util.LinkedHashMap
import java.util.UUID

/**
 * On-disk spool for command output that was too large for the live capture window.
 * A handle lets a later run_command slice/grep the same bytes without re-running the
 * command. The cache is deliberately process-owned and bounded; callers fall back to
 * the original command if a handle has been evicted.
 */
class CommandOutputCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxStreamBytes: Int = MAX_STREAM_BYTES,
    private val maxTotalBytes: Int = MAX_TOTAL_BYTES,
) {
    data class CachedOutput(
        val id: String,
        val stdout: String,
        val stderr: String,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        val workspace: String?,
    )

    private data class Meta(
        val id: String,
        val workspace: String?,
        val bytes: Int,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
    )

    private val entries = LinkedHashMap<String, Meta>(16, 0.75f, true)
    private var totalBytes = 0

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxStreamBytes > 0) { "maxStreamBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        directory.mkdirs()
    }

    @Synchronized
    fun put(stdout: String, stderr: String, workspace: String? = null, force: Boolean = false): String? {
        if (!force && !worthCaching(stdout, stderr)) return null
        val streamLimit = minOf(maxStreamBytes, maxTotalBytes)
        val out = clip(stdout, streamLimit)
        val err = clip(stderr, streamLimit)
        val id = UUID.randomUUID().toString()
        val meta = Meta(
            id = id,
            workspace = workspace,
            bytes = out.text.toByteArray(Charsets.UTF_8).size +
                err.text.toByteArray(Charsets.UTF_8).size,
            stdoutTruncated = out.truncated,
            stderrTruncated = err.truncated,
        )
        try {
            file(id, "stdout").writeText(out.text, Charsets.UTF_8)
            file(id, "stderr").writeText(err.text, Charsets.UTF_8)
            file(id, "meta").writeText(workspace.orEmpty(), Charsets.UTF_8)
        } catch (e: Exception) {
            delete(id)
            throw e
        }
        entries[id] = meta
        totalBytes += meta.bytes
        evictToBudget()
        return if (entries.containsKey(id)) id else null
    }

    @Synchronized
    fun get(id: String): CachedOutput? {
        val meta = entries[id] ?: return null
        val outFile = file(id, "stdout")
        val errFile = file(id, "stderr")
        if (!outFile.isFile || !errFile.isFile) {
            remove(id)
            return null
        }
        val stdout = outFile.readText(Charsets.UTF_8)
        val stderr = errFile.readText(Charsets.UTF_8)
        return CachedOutput(
            id = id,
            stdout = stdout,
            stderr = stderr,
            stdoutTruncated = meta.stdoutTruncated,
            stderrTruncated = meta.stderrTruncated,
            workspace = meta.workspace,
        )
    }

    @Synchronized
    fun remove(id: String) {
        entries.remove(id)?.let { totalBytes -= it.bytes }
        delete(id)
    }

    @Synchronized
    fun clearForWorkspace(workspace: String) {
        entries.values.filter { it.workspace == workspace }.map { it.id }
            .forEach(::remove)
    }

    @Synchronized
    fun clear() {
        entries.keys.toList().forEach(::remove)
        entries.clear()
        totalBytes = 0
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun worthCaching(stdout: String, stderr: String): Boolean =
        stdout.toByteArray(Charsets.UTF_8).size > CACHE_THRESHOLD_BYTES ||
            stderr.toByteArray(Charsets.UTF_8).size > CACHE_THRESHOLD_BYTES ||
            lineCount(stdout) > CACHE_THRESHOLD_LINES ||
            lineCount(stderr) > CACHE_THRESHOLD_LINES

    private fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

    private fun clip(text: String, limit: Int): Clipped {
        if (text.toByteArray(Charsets.UTF_8).size <= limit) return Clipped(text, false)
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (text.substring(0, mid).toByteArray(Charsets.UTF_8).size <= limit) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return Clipped(text.substring(0, low), true)
    }

    private data class Clipped(val text: String, val truncated: Boolean)

    private fun evictToBudget() {
        while (entries.size > maxEntries || totalBytes > maxTotalBytes) {
            val eldest = entries.keys.firstOrNull() ?: break
            remove(eldest)
        }
    }

    private fun file(id: String, suffix: String): File = File(directory, "$id.$suffix")

    private fun delete(id: String) {
        file(id, "stdout").delete()
        file(id, "stderr").delete()
        file(id, "meta").delete()
    }

    companion object {
        const val MAX_ENTRIES = 8
        const val MAX_STREAM_BYTES = 1 * 1_024 * 1_024
        const val MAX_TOTAL_BYTES = 4 * 1_024 * 1_024
        const val CACHE_THRESHOLD_BYTES = 8 * 1_024
        const val CACHE_THRESHOLD_LINES = 100
    }
}
