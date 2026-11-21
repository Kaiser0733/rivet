package com.kaiser.rivet.replay

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class LastReplayExport(
    val format: ReplayFormat,
    val path: String,
    val watermark: Boolean,
)

/**
 * Share means "the replay I just made", not "a fresh video". With nothing exported
 * for this run yet it keeps the old default of rendering a video first.
 */
internal fun replayFormatFor(
    choice: ReplayChoice,
    prepared: ReplayFormat?,
    last: LastReplayExport?,
): ReplayFormat = when (choice) {
    ReplayChoice.Video -> ReplayFormat.Video
    ReplayChoice.Gif -> ReplayFormat.Gif
    ReplayChoice.ShareSheet -> prepared ?: last?.format ?: ReplayFormat.Video
}

/** Which remembered export may be reused as-is for this choice and watermark setting. */
internal fun reusableReplay(
    choice: ReplayChoice,
    prepared: LastReplayExport?,
    last: LastReplayExport?,
    watermark: Boolean,
    exists: (String) -> Boolean,
): LastReplayExport? {
    val format = replayFormatFor(choice, prepared?.format, last)
    return listOfNotNull(prepared, last).firstOrNull {
        it.format == format && it.watermark == watermark && exists(it.path)
    }
}

@Serializable
private data class StoredExport(
    val runKey: String = "",
    val format: String = "Video",
    val path: String = "",
    val watermark: Boolean = true,
)

/** Remembers the last export of each run so the sheet can reopen on the same file. */
internal class ReplayLastExportStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(directory, "last-export.json")

    fun load(runKey: String): LastReplayExport? = runCatching {
        if (!file.isFile) return null
        val stored = json.decodeFromString(StoredExport.serializer(), file.readText())
        if (stored.runKey != runKey) return null
        val format = ReplayFormat.entries.firstOrNull { it.name == stored.format } ?: return null
        LastReplayExport(format, stored.path, stored.watermark)
    }.getOrNull()

    fun save(runKey: String, export: LastReplayExport) {
        runCatching {
            if (!directory.isDirectory) directory.mkdirs()
            val stored = StoredExport(runKey, export.format.name, export.path, export.watermark)
            file.writeText(json.encodeToString(StoredExport.serializer(), stored))
        }
    }
}
