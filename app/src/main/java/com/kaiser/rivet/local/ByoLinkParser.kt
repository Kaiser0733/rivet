package com.kaiser.rivet.local

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class ParsedByoLink(
    val repo: String,
    val revision: String,
    val file: String?,
)

internal object ByoLinkParser {
    private val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun parse(rawUrl: String): ParsedByoLink {
        val url = rawUrl.trim()
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Paste an https://huggingface.co link.")
        }
        if (uri.scheme?.equals("https", ignoreCase = true) != true) {
            throw IllegalArgumentException("Paste an https://huggingface.co link.")
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) ||
            (host != "huggingface.co" && host != "www.huggingface.co")) {
            throw IllegalArgumentException("Only Hugging Face links are supported here.")
        }
        val rawSegments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        if (rawSegments.size < 2) {
            throw IllegalArgumentException("Only Hugging Face links are supported here.")
        }
        val owner = decodePercent(rawSegments[0])
        val repoName = decodePercent(rawSegments[1])
        val repo = "$owner/$repoName"
        if (!REPO_PATTERN.matches(repo)) {
            throw IllegalArgumentException("That does not look like a Hugging Face repo.")
        }
        if (rawSegments.size == 2) {
            return ParsedByoLink(repo = repo, revision = "main", file = null)
        }
        val marker = rawSegments[2]
        if (marker != "blob" && marker != "resolve") {
            return ParsedByoLink(repo = repo, revision = "main", file = null)
        }
        if (rawSegments.size < 5) {
            throw IllegalArgumentException("That link is not a GGUF file. Choose a .gguf file or a repo with one.")
        }
        val revision = decodePercent(rawSegments[3])
        val fileSegments = rawSegments.drop(4).map { decodePercent(it) }
        if (revision.isBlank() || revision == "." || revision == ".." ||
            fileSegments.any { it.isBlank() || it == "." || it == ".." }) {
            throw IllegalArgumentException("That link is not a GGUF file. Choose a .gguf file or a repo with one.")
        }
        val file = fileSegments.joinToString("/")
        if (!file.lowercase(Locale.ROOT).endsWith(".gguf")) {
            throw IllegalArgumentException("That link is not a GGUF file. Choose a .gguf file or a repo with one.")
        }
        return ParsedByoLink(repo = repo, revision = revision, file = file)
    }

    fun downloadUrl(repo: String, revision: String = "main", file: String): String =
        URI("https", null, "huggingface.co", -1, "/$repo/resolve/$revision/$file", "download=true", null)
            .toASCIIString()

    /** Decodes `%XX` UTF-8 bytes without altering literal `+` characters in filenames. */
    private fun decodePercent(segment: String): String {
        if ('%' !in segment) return segment
        val out = StringBuilder(segment.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < segment.length) {
            val ch = segment[i]
            if (ch == '%') {
                if (i + 2 >= segment.length) {
                    throw IllegalArgumentException("That does not look like a Hugging Face link.")
                }
                val hi = Character.digit(segment[i + 1], 16)
                val lo = Character.digit(segment[i + 2], 16)
                if (hi < 0 || lo < 0) {
                    throw IllegalArgumentException("That does not look like a Hugging Face link.")
                }
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                if (bytes.size() > 0) {
                    out.append(String(bytes.toByteArray(), StandardCharsets.UTF_8))
                    bytes.reset()
                }
                out.append(ch)
                i++
            }
        }
        if (bytes.size() > 0) {
            out.append(String(bytes.toByteArray(), StandardCharsets.UTF_8))
        }
        return out.toString()
    }
}
