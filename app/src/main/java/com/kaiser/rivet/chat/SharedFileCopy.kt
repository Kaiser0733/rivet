package com.kaiser.rivet.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kaiser.rivet.runtime.ProjectDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

internal object SharedFileCopy {
    const val INLINE_BYTES = 8 * 1024

    fun inbox(context: Context) = File(context.cacheDir, "shared-in/inbox")

    suspend fun copy(context: Context, source: Uri, fallback: String): File = withContext(Dispatchers.IO) {
        if (source.scheme != "content") throw IOException("Rivet couldn't open this attachment. Share it from its app.")
        val resolver = context.contentResolver
        val displayName = runCatching {
            resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val name = displayName?.substringAfterLast('/')?.replace(Regex("[\\\\\\p{Cntrl}]"), "_")?.take(120)
            ?.takeIf { it.isNotBlank() && it !in setOf(".", "..") } ?: fallback
        val dir = inbox(context)
        dir.mkdirs()
        val preferred = File(dir, name)
        val target = if (preferred.createNewFile()) preferred else File(dir, "${UUID.randomUUID()}-$name")
        try {
            val input = resolver.openInputStream(source) ?: throw IOException("No shared file stream")
            input.use {
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > ProjectDownloader.MAX_DOWNLOAD_BYTES) throw TooLarge()
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        target
    }

    fun draft(file: File, mime: String): String {
        val path = "inbox/${file.name}"
        val text = mime.startsWith("text/") ||
            mime in setOf("application/json", "application/xml", "application/javascript", "application/x-shellscript") ||
            file.extension.lowercase() in setOf("kt", "kts", "java", "py", "js", "ts", "rs", "go", "sh", "md", "txt",
                "json", "xml", "yaml", "yml", "toml", "html", "css", "c", "cpp", "h", "rb", "sql")
        if (!text) return "Shared file saved as `$path`."
        val size = file.length()
        val bytes = ByteArray(minOf(size, INLINE_BYTES.toLong()).toInt())
        DataInputStream(file.inputStream()).use { it.readFully(bytes) }
        val excerpt = bytes.toString(Charsets.UTF_8)
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(excerpt).maxOfOrNull { it.value.length } ?: 0) + 1))
        val label = if (size > INLINE_BYTES) "Shared file `$path` (truncated, full file saved)." else "Shared file `$path`"
        return "$label\n\n$fence\n$excerpt\n$fence"
    }

    class TooLarge : IOException("Shared files must be 64 MiB or smaller.")
}
