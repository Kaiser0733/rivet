package com.kaiser.rivet.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import com.kaiser.rivet.agent.AgentWorkspaceFailure
import com.kaiser.rivet.agent.SafAgentWorkspace
import com.kaiser.rivet.agent.presentablePath
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface FilePreviewResult {
    data class Text(val content: String, val markdown: Boolean) : FilePreviewResult
    data class Image(val bitmap: Bitmap) : FilePreviewResult
    data class Unavailable(val size: Long?, val reason: String) : FilePreviewResult
    data object Missing : FilePreviewResult
    data object WrongProject : FilePreviewResult
}

internal object WorkspaceFilePreview {
    private const val IMAGE_BYTES = 8L * 1024 * 1024
    private const val IMAGE_SIDE = 1600

    suspend fun isFile(workspace: SafWorkspace, relative: String): Boolean = try {
        statFile(workspace, presentablePath(relative)) != null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun statFile(workspace: SafWorkspace, path: WorkspacePath): WorkspaceEntry? =
        workspace.stat(path).takeUnless { it.directory }

    suspend fun read(
        workspace: SafWorkspace,
        relative: String,
        onImageBounds: suspend (Size) -> Unit = {},
    ): FilePreviewResult {
        val path = presentablePath(relative)
        val files = SafAgentWorkspace(workspace)
        return try {
            val entry = statFile(workspace, path) ?: return FilePreviewResult.Missing
            val type = previewType(entry.mimeType, path.name)
            when (type) {
                "Markdown", "Text" -> {
                    val snapshot = files.read(path.value)
                    FilePreviewResult.Text(snapshot.text, markdown = type == "Markdown")
                }
                "Image" -> {
                    val output = ByteArrayOutputStream()
                    workspace.copyFileTo(path, output, maxBytes = IMAGE_BYTES)
                    val bitmap = withContext(Dispatchers.IO) {
                        val bytes = output.toByteArray()
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
                        onImageBounds(Size(options.outWidth, options.outHeight))
                        var sample = 1
                        while (maxOf(options.outWidth, options.outHeight) / sample > IMAGE_SIDE) sample *= 2
                        options.inSampleSize = sample
                        options.inJustDecodeBounds = false
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                    if (bitmap != null) FilePreviewResult.Image(bitmap)
                    else FilePreviewResult.Unavailable(entry.size, "This image can't be previewed.")
                }
                else -> FilePreviewResult.Unavailable(entry.size, "This file type can't be previewed.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AgentWorkspaceFailure) {
            failure(e.code, files, path)
        } catch (e: WorkspaceFailure) {
            failure(e.reason.name.lowercase(), files, path)
        }
    }

    private suspend fun failure(code: String, files: SafAgentWorkspace, path: WorkspacePath): FilePreviewResult {
        if (code == "missing" || code == "not_file") return FilePreviewResult.Missing
        val size = try { files.stat(path.value).size }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val text = when (code) {
            "binary" -> "This file type can't be previewed."
            "too_large", "limit" -> "This file is too large to preview."
            else -> "Rivet couldn't read this file."
        }
        return FilePreviewResult.Unavailable(size, text)
    }

    private fun previewType(mime: String, name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension in setOf("md", "markdown", "mdx") -> "Markdown"
            extension == "svg" -> "File"
            mime.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif") -> "Image"
            extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt") -> "File"
            else -> "Text"
        }
    }
}
