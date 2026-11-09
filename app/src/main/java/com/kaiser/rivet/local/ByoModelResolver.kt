package com.kaiser.rivet.local

import com.kaiser.rivet.provider.http
import com.kaiser.rivet.provider.quick
import com.kaiser.rivet.provider.readBoundedBody
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class ByoResolvedModel(val entry: LocalModelEntry, val sha256: String?)

/**
 * Bring-your-own is deliberately narrow: an HTTPS Hugging Face repo or file link,
 * checked for a GGUF magic before any large download starts.
 */
class ByoModelResolver(private val client: OkHttpClient = http) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(rawUrl: String): ByoResolvedModel {
        val parsed = parse(rawUrl)
        val file = parsed.file ?: findSingleGguf(parsed.repo)
        val downloadUrl = "https://huggingface.co/${parsed.repo}/resolve/main/$file?download=true"
        val (size, sha) = inspect(downloadUrl)
        val name = file.substringAfterLast('/').removeSuffix(".gguf")
            .replace('-', ' ').replace('_', ' ').trim().ifBlank { "Custom model" }
        val params = Regex("(?<![0-9])([0-9]+(?:\\.[0-9]+)?)B", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1) ?: "1"
        val paramsB = params.toDoubleOrNull() ?: 1.0
        val ramFloor = when {
            paramsB >= 8 -> 12_288
            paramsB >= 4 -> 8_192
            paramsB >= 2 -> 6_144
            else -> 4_096
        }
        val contextMax = when {
            paramsB >= 4 -> 16_384
            paramsB >= 1 -> 8_192
            else -> 4_096
        }
        return ByoResolvedModel(
            LocalModelEntry(
                name = name,
                repo = parsed.repo,
                file = file,
                size = size,
                params = params,
                ramFloor = ramFloor,
                contextMax = contextMax,
                categories = listOf(LocalCategory.General),
                toolCalling = LocalToolCalling.Prompted,
                blurb = "Brought from Hugging Face. Tool use depends on this model's chat template.",
                sha256 = sha,
            ),
            sha,
        )
    }

    private data class ParsedLink(val repo: String, val file: String?)

    private fun parse(rawUrl: String): ParsedLink {
        val url = rawUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw LocalFailure("Paste an https://huggingface.co link.")
        }
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val parts = withoutQuery.removePrefix("https://").removePrefix("http://").split('/')
            .filter { it.isNotBlank() }
        if (parts.size < 3 || !parts[0].equals("huggingface.co", true) &&
            !parts[0].equals("www.huggingface.co", true)) {
            throw LocalFailure("Only Hugging Face links are supported here.")
        }
        val repo = "${parts[1]}/${parts[2]}"
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) {
            throw LocalFailure("That does not look like a Hugging Face repo.")
        }
        var file: String? = null
        val blob = parts.indexOfFirst { it == "blob" || it == "resolve" }
        if (blob >= 0 && parts.size > blob + 2) {
            file = parts.drop(blob + 2).joinToString("/").let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.name())
            }
        }
        if (file != null && (!file.lowercase(Locale.ROOT).endsWith(".gguf") ||
                file.split('/').any { it == ".." || it.isBlank() })) {
            throw LocalFailure("That link is not a GGUF file. Choose a .gguf file or a repo with one.")
        }
        return ParsedLink(repo, file)
    }

    private fun findSingleGguf(repo: String): String {
        val request = Request.Builder()
            .url("https://huggingface.co/api/models/$repo?blobs=true").get().build()
        client.quick().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw LocalFailure("Hugging Face did not return that repo.")
            val root = json.parseToJsonElement(response.readBoundedBody(4 * 1024 * 1024)
                ?: throw LocalFailure("Hugging Face returned an empty repo.")).jsonObject
            val files = root["siblings"]?.jsonArray?.mapNotNull {
                it.jsonObject["rfilename"]?.jsonPrimitive?.content
            }?.filter { it.lowercase(Locale.ROOT).endsWith(".gguf") }.orEmpty()
            if (files.isEmpty()) throw LocalFailure("That repo has no GGUF files.")
            files.firstOrNull { it.lowercase(Locale.ROOT).contains("q4_k_m") }?.let { return it }
            if (files.size == 1) return files.single()
            throw LocalFailure("That repo has several GGUF files. Paste the link to one file.")
        }
    }

    private fun inspect(downloadUrl: String): Pair<Long, String?> {
        val request = Request.Builder().url(downloadUrl)
            .header("Range", "bytes=0-3")
            .header("Accept-Encoding", "identity")
            .get().build()
        client.quick().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw LocalFailure("Hugging Face refused that file (HTTP ${response.code}).")
            }
            val source = response.body?.source()
                ?: throw LocalFailure("Hugging Face returned an empty file.")
            val bytes = try { source.readByteArray(4) } catch (e: IOException) { ByteArray(0) }
            if (bytes.size < 4 || !bytes.contentEquals(byteArrayOf('G'.code.toByte(),
                    'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                throw LocalFailure("That file is not a GGUF model. Nothing was downloaded.")
            }
            val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("X-Linked-Size")?.toLongOrNull()
                ?: response.body?.contentLength()?.takeIf { it > 4 }
                ?: throw LocalFailure("Hugging Face did not report a file size.")
            if (total <= 0L) throw LocalFailure("That GGUF file has no usable size.")
            val sha = DownloadPolicy.checksumFromEtag(
                response.header("ETag") ?: response.header("X-Linked-ETag"))
            return total to sha
        }
    }

}
