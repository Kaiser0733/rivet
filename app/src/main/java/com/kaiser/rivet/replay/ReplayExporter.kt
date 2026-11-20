package com.kaiser.rivet.replay

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.kaiser.rivet.runtime.PhoneIntents
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ReplayFile(val file: File, val format: ReplayFormat, val watermark: Boolean)

internal class ReplayExporter(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "run-replays")

    suspend fun render(
        story: ReplayStory,
        palette: ReplayFramePalette,
        format: ReplayFormat,
        watermark: Boolean,
    ): ReplayFile = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val checkActive = { job.ensureActive() }
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter {
            it.name.startsWith("Rivet-replay-") && it.lastModified() < expiry
        }?.forEach { it.delete() }
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
                    var elapsedMs = 0L
                    story.beats.forEach { beat ->
                        checkActive()
                        val bitmap = frames.render(beat, watermark, width, height)
                        try { bitmap.getPixels(colors, 0, width, 0, 0, width, height) }
                        finally { bitmap.recycle() }
                        val delayCs = (elapsedMs + beat.holdMs) / 10 - elapsedMs / 10
                        elapsedMs += beat.holdMs
                        writer.addFrame(GifWriter.indexPixels(colors), delayCs * 10)
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

    suspend fun distribute(replay: ReplayFile, choice: ReplayChoice): String =
        when (choice.destination(Build.VERSION.SDK_INT)) {
            ReplayDestination.Share -> try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.shared-files", replay.file)
                val phone = PhoneIntents(app, resolveFile = { null }, customTabOpener = { false })
                phone.shareFile(PhoneIntents.ContentFile(uri, replay.format.mime))
                "Share sheet opened."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "Couldn't open the share sheet."
            }
            ReplayDestination.Gallery -> if (Build.VERSION.SDK_INT >= 29) {
                saveToGallery(replay)
                "Saved to gallery."
            } else {
                error("Gallery needs Android 10 or newer")
            }
        }

    @RequiresApi(29)
    private suspend fun saveToGallery(replay: ReplayFile) = withContext(Dispatchers.IO) {
        val video = replay.format == ReplayFormat.Video
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, replay.file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, replay.format.mime)
            val folder = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Rivet")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = checkNotNull(resolver.insert(collection, values))
        var published = false
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                replay.file.inputStream().use { it.copyTo(output) }
            }
            currentCoroutineContext().ensureActive()
            val update = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, update, null, null) == 1)
            published = true
        } finally {
            if (!published) runCatching { resolver.delete(uri, null, null) }
        }
    }
}
