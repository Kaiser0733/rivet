package com.kaiser.rivet.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Owns full command streams in the app cache and exposes workspace-scoped paging handles. */
class CommandOutputCache(private val directory: File) {
    class CachedOutput internal constructor(
        private val root: File,
        val workspace: String?,
    ) {
        val id get() = root.name
        val stdout get() = preview(stdoutFile)
        val stderr get() = preview(stderrFile)
        val stdoutTruncated get() = stdoutFile.length() > PREVIEW_BYTES
        val stderrTruncated get() = stderrFile.length() > PREVIEW_BYTES
        private val stdoutFile get() = File(root, "stdout")
        private val stderrFile get() = File(root, "stderr")

        private fun preview(file: File): String = RandomAccessFile(file, "r").use { input ->
            val bytes = ByteArray(minOf(input.length(), PREVIEW_BYTES.toLong()).toInt())
            input.readFully(bytes)
            bytes.copyOf(completeUtf8Prefix(bytes)).toString(Charsets.UTF_8)
        }

        internal fun read(offset: Long, stderr: Boolean): BytePage =
            RandomAccessFile(if (stderr) stderrFile else stdoutFile, "r").use { file ->
                val total = file.length()
                var start = offset.coerceIn(0, total)
                file.seek(start)
                while (start < total) {
                    val value = file.read()
                    if ((value and 0xc0) != 0x80) break
                    start++
                }
                file.seek(start)
                val bytes = ByteArray(minOf(total - start, PAGE_BYTES.toLong()).toInt())
                file.readFully(bytes)
                val end = if (start + bytes.size < total) completeUtf8Prefix(bytes) else bytes.size
                BytePage(bytes.copyOf(end).toString(Charsets.UTF_8), start, start + end, total)
            }

        private fun completeUtf8Prefix(bytes: ByteArray): Int {
            var leader = bytes.lastIndex
            while (leader >= 0 && (bytes[leader].toInt() and 0xc0) == 0x80) leader--
            if (leader < 0) return bytes.size
            val byte = bytes[leader].toInt() and 0xff
            val expected = when (byte) {
                in 0xc2..0xdf -> 2
                in 0xe0..0xef -> 3
                in 0xf0..0xf4 -> 4
                else -> 1
            }
            return if (bytes.size - leader < expected) leader else bytes.size
        }
    }

    internal data class BytePage(val text: String, val start: Long, val end: Long, val total: Long)

    internal class Spool(private val root: File, private val workspace: String?) {
        private var stdout: FileOutputStream? = null
        private var stderr: FileOutputStream? = null
        private var failed = false

        private var opened = false

        private fun open() {
            opened = true
            try {
                if (!root.mkdirs()) throw IOException("Could not create command output directory")
                stdout = FileOutputStream(File(root, "stdout"))
                stderr = FileOutputStream(File(root, "stderr"))
            } catch (_: IOException) {
                failed = true
            }
        }

        @Synchronized fun append(isStderr: Boolean, bytes: ByteArray, size: Int) {
            if (!opened) open()
            if (failed) return
            try {
                (if (isStderr) stderr else stdout)?.write(bytes, 0, size)
            } catch (_: IOException) {
                failed = true
            }
        }

        @Synchronized fun finish(keep: Boolean): String? {
            if (!opened) return null
            for (stream in listOf(stdout, stderr)) {
                try { stream?.close() } catch (_: IOException) { failed = true }
            }
            if (failed || !keep) {
                root.deleteRecursively()
                if (failed) throw IOException("Could not store full command output")
                return null
            }
            try {
                // A reader can discover the spill only after both streams have closed.
                File(root, "owner").writeText(workspace.orEmpty())
                return root.name
            } catch (error: IOException) {
                root.deleteRecursively()
                throw error
            }
        }
    }

    internal fun begin(workspace: String?): Spool =
        Spool(File(directory, UUID.randomUUID().toString()), workspace)

    fun put(stdout: String, stderr: String, workspace: String? = null, force: Boolean = false): String? {
        if (!force && !worthCaching(stdout, stderr)) return null
        val spool = begin(workspace)
        val out = stdout.toByteArray()
        val err = stderr.toByteArray()
        spool.append(false, out, out.size)
        spool.append(true, err, err.size)
        return spool.finish(keep = true)
    }

    fun get(id: String): CachedOutput? {
        if (!ID.matches(id)) return null
        return load(File(directory, id))
    }

    private fun load(root: File): CachedOutput? = try {
        if (!File(root, "stdout").isFile || !File(root, "stderr").isFile) null
        else CachedOutput(root, File(root, "owner").readText().takeIf(String::isNotEmpty))
    } catch (_: IOException) { null }

    fun clearForWorkspace(workspace: String) {
        directory.listFiles().orEmpty().filter { load(it)?.workspace == workspace }.forEach(File::deleteRecursively)
    }

    fun clear() {
        directory.listFiles().orEmpty().filter { load(it) != null }.forEach(File::deleteRecursively)
    }

    fun size(): Int = directory.listFiles().orEmpty().count { load(it) != null }

    companion object {
        private val ID = Regex("[0-9a-f-]{36}")
        private const val PREVIEW_BYTES = 1_024 * 1_024
        // Retain space for JSON quoting of control characters on the tool wire.
        private const val PAGE_BYTES = 3 * 1_024

        internal fun worthCaching(stdout: String, stderr: String): Boolean =
            stdout.toByteArray().size > 8 * 1_024 || stderr.toByteArray().size > 8 * 1_024 ||
                lineCount(stdout) > 100 || lineCount(stderr) > 100

        private fun lineCount(text: String): Int = if (text.isEmpty()) 0
            else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
    }
}
