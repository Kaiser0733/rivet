package com.kaiser.rivet.agent

import android.graphics.BitmapFactory
import com.kaiser.rivet.provider.VisionSupport
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceFailure
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProjectImageRead(
    val isImage: Boolean,
    val note: String,
    val input: ProjectImageInput? = null,
)

private const val PROJECT_IMAGE_BYTES = 8L * 1024 * 1024
private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif")

internal suspend fun projectImageRead(
    workspace: SafWorkspace,
    cache: File,
    support: VisionSupport,
    relative: String,
): ProjectImageRead = withContext(Dispatchers.IO) {
    val path = presentablePath(relative)
    val entry = try {
        workspace.stat(path)
    } catch (_: WorkspaceFailure) {
        return@withContext ProjectImageRead(false, "The project file could not be opened.")
    }
    if (entry.directory || (!entry.mimeType.startsWith("image/") &&
            path.name.substringAfterLast('.', "").lowercase() !in imageExtensions)) {
        return@withContext ProjectImageRead(false, "This file is not an image.")
    }
    when (support) {
        is VisionSupport.Unsupported -> return@withContext ProjectImageRead(true, "This model cannot view images.")
        VisionSupport.Unknown -> return@withContext ProjectImageRead(true,
            "Rivet could not confirm that this model can view images.")
        VisionSupport.Supported -> Unit
    }
    val unavailable = ProjectImageRead(true, "Rivet couldn't load this image for the model.")
    val directory = File(cache, "project-vision")
    var temporary: File? = null
    try {
        directory.mkdirs()
        val file = File.createTempFile("image-", ".tmp", directory)
        temporary = file
        val fingerprint = file.outputStream().use { workspace.copyFileTo(path, it, PROJECT_IMAGE_BYTES) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext unavailable
        val saved = File(directory, fingerprint.sha256)
        if (!saved.isFile && !file.renameTo(saved)) return@withContext unavailable
        ProjectImageRead(true, "Attached for vision.", ProjectImageInput(relative, saved.absolutePath))
    } catch (_: IOException) {
        unavailable
    } catch (_: WorkspaceFailure) {
        unavailable
    } finally {
        temporary?.delete()
    }
}
