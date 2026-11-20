package com.kaiser.rivet.starter

import com.kaiser.rivet.workspace.WorkspacePath
import java.io.InputStream
import java.util.zip.ZipInputStream

internal class StarterInstallFailure(message: String) : IllegalArgumentException(message)

internal data class StarterEntry(val path: WorkspacePath, val bytes: ByteArray?)

internal object StarterInstall {
    const val NOT_EMPTY = "That folder already has files. Pick an empty folder for a starter."
    private const val BAD_ARCHIVE = "This starter is damaged. Open the gallery and choose it again."
    private const val MAX_UNPACKED_BYTES = 1_000_000
    private const val MAX_ENTRIES = 128

    fun load(option: StarterOption, openBundled: (String) -> InputStream): List<StarterEntry> {
        val starter = option.starter
        val bytes = try {
            (option.cachedZip?.inputStream() ?: openBundled("${StarterCatalog.DIRECTORY}/${starter.zip}"))
                .use { it.readStarterBytes(starter.bytes) }
        } catch (_: Exception) {
            throw StarterInstallFailure(BAD_ARCHIVE)
        }
        if (bytes.size != starter.bytes ||
            (option.cachedZip != null && !starter.sha256.equals(starterSha256(bytes), ignoreCase = true))) {
            throw StarterInstallFailure(BAD_ARCHIVE)
        }
        return read(bytes)
    }

    fun read(archive: ByteArray): List<StarterEntry> {
        val files = linkedMapOf<WorkspacePath, ByteArray>()
        val entries = mutableSetOf<WorkspacePath>()
        try {
            require(archive.size in 1..StarterCatalog.MAX_ZIP_BYTES)
            ZipInputStream(archive.inputStream()).use { zip ->
                var remaining = MAX_UNPACKED_BYTES
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                    require(name.isNotEmpty() && ':' !in name)
                    val path = WorkspacePath.parse(name)
                    require(entries.add(path) && entries.size <= MAX_ENTRIES)
                    val bytes = zip.readStarterBytes(if (entry.isDirectory) 0 else remaining)
                    if (!entry.isDirectory) {
                        require(0.toByte() !in bytes)
                        bytes.decodeToString(throwOnInvalidSequence = true)
                        files[path] = bytes
                        remaining -= bytes.size
                    }
                    zip.closeEntry()
                }
            }
            for (path in entries) {
                var parent = path.parent()
                while (!parent.isRoot) {
                    require(parent !in files)
                    parent = parent.parent()
                }
            }
            for (name in listOf("README.md", "index.html")) {
                require(files[WorkspacePath.parse(name)]?.isNotEmpty() == true)
            }
            val directories = entries.flatMap { path ->
                val directory = if (path in files) path.parent() else path
                generateSequence(directory) { it.parent() }.takeWhile { !it.isRoot }.toList()
            }.distinct().sortedBy { it.segments.size }
            require(directories.size + files.size <= MAX_ENTRIES)
            return directories.map { StarterEntry(it, null) } +
                files.map { (path, bytes) -> StarterEntry(path, bytes) }
        } catch (_: Exception) {
            throw StarterInstallFailure(BAD_ARCHIVE)
        }
    }

    suspend fun copy(files: List<StarterEntry>, isEmpty: suspend () -> Boolean, write: suspend (StarterEntry) -> Unit) {
        if (!isEmpty()) throw StarterInstallFailure(NOT_EMPTY)
        files.forEach { write(it) }
    }
}
