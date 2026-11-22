package com.kaiser.rivet.chat

import java.net.URI
import java.util.Locale

internal sealed interface SharedContent {
    data class Text(val text: String) : SharedContent
    data class Image(val uri: String) : SharedContent
    data class File(val uri: String, val mime: String) : SharedContent
    data class Zip(val uri: String) : SharedContent
    data class GitHubLink(val owner: String, val repo: String, val url: String) : SharedContent

    companion object {
        private const val SEND = "android.intent.action.SEND"
        private const val VIEW = "android.intent.action.VIEW"
        private val url = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
        private val identifier = Regex("[A-Za-z0-9_.-]+")

        fun classify(action: String?, mimeType: String?, text: String?, uri: String?): SharedContent? {
            if (action != SEND && action != VIEW) return null
            val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (uri != null) {
                val source = runCatching { URI(uri) }.getOrNull() ?: return null
                if (source.scheme !in setOf("content", "file")) return null
                if (mime == "application/zip" || mime == "application/x-zip-compressed" ||
                    action == VIEW && source.path?.endsWith(".zip", ignoreCase = true) == true) return Zip(uri)
                if (mime?.startsWith("image/") == true) return Image(uri)
                return File(uri, mime ?: "application/octet-stream")
            }
            if (action != SEND || text.isNullOrBlank()) return null
            return url.findAll(text).firstNotNullOfOrNull { match ->
                val address = match.value.trimEnd('.', ',', ')', ']', '!', '?')
                val parsed = runCatching { URI(address) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (!parsed.host.equals("github.com", ignoreCase = true) || parsed.userInfo != null) {
                    return@firstNotNullOfOrNull null
                }
                val segments = parsed.path.orEmpty().removePrefix("/").split('/')
                val owner = segments.getOrNull(0)?.takeIf { identifier.matches(it) && it !in setOf(".", "..") }
                val repo = segments.getOrNull(1)?.removeSuffix(".git")
                    ?.takeIf { identifier.matches(it) && it !in setOf(".", "..") }
                if (owner != null && repo != null) GitHubLink(owner, repo, address) else null
            } ?: Text(text)
        }
    }
}

data class SharedDraft(val id: String, val text: String, val imageName: String? = null)

/**
 * A shared attachment is read through a transient URI grant. The blanket "couldn't
 * read this attachment" hid which of the three things went wrong, so say it.
 */
internal fun shareFailureNotice(error: Throwable): String = when (error) {
    is SecurityException ->
        "Rivet doesn't have permission to read this attachment. Share it again and allow access."
    is java.io.FileNotFoundException ->
        "That attachment is no longer available. Share it again."
    else -> "Rivet couldn't read this attachment. Try sharing it again."
}

internal data class ComposerAttachment(val name: String, val image: Boolean) {
    fun messageText(draft: String): String {
        if (image) return draft.ifBlank { "Attached image: $name" }
        return listOf(draft, "Attached file: inbox/$name").filter(String::isNotBlank).joinToString("\n\n")
    }
}
