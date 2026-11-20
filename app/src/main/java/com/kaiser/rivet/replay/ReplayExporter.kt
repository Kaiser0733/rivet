package com.kaiser.rivet.replay

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ReplayFile(val file: File, val format: ReplayFormat, val watermark: Boolean)

internal class ReplayExporter(context: Context) {
    private val directory = File(context.cacheDir, "run-replays")

    suspend fun render(
        story: ReplayStory,
        palette: ReplayFramePalette,
        format: ReplayFormat,
        watermark: Boolean,
    ): ReplayFile = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val checkActive = { job.ensureActive() }
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val output = File.createTempFile("Rivet-replay-", ".${format.extension}", directory)
        try {
            val frames = ReplayFrames(story, palette)
            when (format) {
                ReplayFormat.Video -> ReplayVideoEncoder().encode(story, frames, watermark, output, checkActive)
                ReplayFormat.Gif -> {
                    val width = GifWriter.MAX_WIDTH
                    val height = GifWriter.MAX_HEIGHT
                    val colors = IntArray(width * height)
                    val writer = GifWriter(width, height)
                    story.beats.forEach { beat ->
                        checkActive()
                        val bitmap = frames.render(beat, watermark, width, height)
                        try { bitmap.getPixels(colors, 0, width, 0, 0, width, height) }
                        finally { bitmap.recycle() }
                        writer.addFrame(GifWriter.indexPixels(colors), beat.holdMs)
                    }
                    output.outputStream().buffered().use(writer::writeTo)
                }
            }
            checkActive()
            ReplayFile(output, format, watermark)
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}
