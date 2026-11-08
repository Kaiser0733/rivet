package com.kaiser.rivet.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kaiser.rivet.provider.await
import com.kaiser.rivet.provider.http
import com.kaiser.rivet.provider.quick
import com.kaiser.rivet.provider.readBoundedBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

private const val GITHUB_API = "https://api.github.com"
private const val GITHUB_ACCEPT = "application/vnd.github+json"
private const val TOO_BIG = "This skill is larger than 20 MB."
private const val ZIP_TOO_BIG = "That zip is larger than 20 MB."
private const val UNSAFE_PATH = "This skill contains a file path Rivet won't write."
private const val ZIP_UNREADABLE = "That file isn't a zip Rivet can read."
private const val NO_SKILL_MD = "That folder doesn't contain a SKILL.md."
private const val GITHUB_UNREACHABLE = "Couldn't reach GitHub. Check the connection and try again."
private const val WRITE_FAILED = "Rivet couldn't write the skill to storage."

/** Copies a shared/copied zip into the cache so the flow can reopen it for each choice. */
internal suspend fun copySharedZip(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val target = File(context.cacheDir, "skill-import.zip")
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw SkillInstallFailure("That file couldn't be opened.")
    try {
        stream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (exceedsInstallCap(total)) throw SkillInstallFailure(ZIP_TOO_BIG)
                    output.write(buffer, 0, read)
                }
            }
        }
    } catch (e: SkillInstallFailure) {
        target.delete()
        throw e
    } catch (e: Exception) {
        target.delete()
        if (e is CancellationException) throw e
        throw SkillInstallFailure(ZIP_UNREADABLE)
    }
    target
}

/** The shared file's name for the review screen; the cache copy's name is not useful. */
internal suspend fun sharedZipName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val queried = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }
    queried?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Shared zip"
}

/** The skill folders inside a zip; one entry means there is nothing to choose. */
internal suspend fun zipSkillCandidates(zip: File): List<String> = withContext(Dispatchers.IO) {
    try {
        ZipFile(zip).use { archive -> zipSkillRoots(Collections.list(archive.entries()).map { it.name }) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        throw SkillInstallFailure(ZIP_UNREADABLE)
    }
}

/**
 * Reads one skill folder out of a zip, validating every entry before keeping it.
 * ponytail: the reviewed skill sits in memory (≤20 MB) so nothing touches the skills
 * folder before the user confirms; stream to a staging dir only if 20 MB proves slow.
 */
internal suspend fun readZipSkill(zip: File, root: String, sourceLabel: String): SkillPayload =
    withContext(Dispatchers.IO) {
        val files = mutableListOf<BundledFile>()
        var total = 0L
        try {
            ZipFile(zip).use { archive ->
                for (entry in Collections.list(archive.entries())) {
                    if (entry.isDirectory || !entry.name.startsWith(root)) continue
                    val relative = entry.name.removePrefix(root)
                    if (relative.isEmpty() || isZipJunk(relative)) continue
                    if (!zipEntryIsSafe(relative)) throw SkillInstallFailure(UNSAFE_PATH)
                    if (files.size >= MAX_SKILL_FILES) {
                        throw SkillInstallFailure("This skill has too many files.")
                    }
                    val remaining = MAX_SKILL_INSTALL_BYTES - total
                    if (entry.size > remaining) throw SkillInstallFailure(TOO_BIG)
                    val bytes = archive.getInputStream(entry).use { it.readCapped(remaining) }
                    total += bytes.size
                    files += BundledFile(relative, bytes)
                }
            }
        } catch (e: SkillInstallFailure) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw SkillInstallFailure(ZIP_UNREADABLE)
        }
        validateSkillPayload(files, installFolderName(root), sourceLabel)
    }

/** Writes the reviewed payload into `skills/<name>/`, replacing an existing copy if allowed. */
internal suspend fun installSkill(payload: SkillPayload, globalRoot: File): Unit =
    withContext(Dispatchers.IO) {
        if (!globalRoot.mkdirs() && !globalRoot.isDirectory) throw SkillInstallFailure(WRITE_FAILED)
        // The parser already enforces the name grammar; re-check at the write boundary so
        // no payload can point the target outside the skills root.
        if (!zipEntryIsSafe(payload.skill.name)) throw SkillInstallFailure(WRITE_FAILED)
        val target = File(globalRoot, payload.skill.name)
        // Stage next to the target and swap, so a failed write never leaves half a skill.
        val staging = File(globalRoot, ".${payload.skill.name}.installing")
        staging.deleteRecursively()
        try {
            for (file in payload.files) {
                if (!zipEntryIsSafe(file.path)) throw SkillInstallFailure(UNSAFE_PATH)
                val output = File(staging, file.path)
                output.parentFile?.mkdirs()
                output.writeBytes(file.bytes)
            }
            if (target.exists() && !target.deleteRecursively()) throw SkillInstallFailure(WRITE_FAILED)
            if (!staging.renameTo(target)) throw SkillInstallFailure(WRITE_FAILED)
        } catch (e: SkillInstallFailure) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw SkillInstallFailure(WRITE_FAILED)
        } finally {
            staging.deleteRecursively()
        }
    }

// ---------------------------------------------------------------- GitHub contents API

private data class GitHubEntry(
    val name: String,
    val path: String,
    val type: String,
    val downloadUrl: String?,
)

/**
 * The skill folders at a repo/subfolder: the folder itself when it holds SKILL.md, else
 * the immediate child folders that do.
 */
internal suspend fun githubCandidates(source: GitHubSource): List<SkillCandidate> =
    withContext(Dispatchers.IO) {
        try {
            val entries = githubContents(source, source.path)
            if (entries.any { it.type == "file" && it.name == "SKILL.md" }) {
                return@withContext listOf(SkillCandidate(source.path))
            }
            entries.filter { it.type == "dir" }
                .filter { githubHasSkillMd(source, it.path) }
                .map { SkillCandidate(it.path) }
        } catch (e: SkillInstallFailure) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw SkillInstallFailure(GITHUB_UNREACHABLE)
        }
    }

/** Downloads a whole skill folder (recursively) and parses its SKILL.md. */
internal suspend fun fetchGitHubSkill(source: GitHubSource, path: String): SkillPayload =
    withContext(Dispatchers.IO) {
        try {
            val files = mutableListOf<BundledFile>()
            collectFolder(source, path, "", files, 0L)
            validateSkillPayload(files, installFolderName(path), source.label)
        } catch (e: SkillInstallFailure) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw SkillInstallFailure(GITHUB_UNREACHABLE)
        }
    }

private suspend fun collectFolder(
    source: GitHubSource,
    path: String,
    prefix: String,
    out: MutableList<BundledFile>,
    startTotal: Long,
): Long {
    var total = startTotal
    for (entry in githubContents(source, path)) {
        val relative = prefix + entry.name
        when (entry.type) {
            "file" -> {
                if (isZipJunk(relative)) continue
                if (!zipEntryIsSafe(relative)) throw SkillInstallFailure(UNSAFE_PATH)
                if (out.size >= MAX_SKILL_FILES) {
                    throw SkillInstallFailure("This skill has too many files.")
                }
                val url = entry.downloadUrl
                    ?: throw SkillInstallFailure("GitHub didn't offer a download for one of the files.")
                val bytes = downloadFile(url, MAX_SKILL_INSTALL_BYTES - total)
                total += bytes.size
                if (exceedsInstallCap(total)) throw SkillInstallFailure(TOO_BIG)
                out += BundledFile(relative, bytes)
            }
            "dir" -> total = collectFolder(source, entry.path, "$relative/", out, total)
        }
    }
    return total
}

private suspend fun githubHasSkillMd(source: GitHubSource, path: String): Boolean {
    http.quick().await(githubRequest(contentsUrl(source, "$path/SKILL.md"))).use { response ->
        return when {
            response.isSuccessful -> true
            response.code == 404 -> false
            else -> throw githubFailure(response)
        }
    }
}

private suspend fun githubContents(source: GitHubSource, path: String): List<GitHubEntry> {
    http.quick().await(githubRequest(contentsUrl(source, path))).use { response ->
        if (!response.isSuccessful) throw githubFailure(response)
        val body = response.readBoundedBody()
            ?: throw SkillInstallFailure("GitHub returned an empty answer.")
        return parseGitHubEntries(body)
    }
}

private suspend fun downloadFile(url: String, remaining: Long): ByteArray {
    val request = try {
        Request.Builder().url(url).build()
    } catch (e: IllegalArgumentException) {
        throw SkillInstallFailure("GitHub returned a link Rivet couldn't follow.")
    }
    http.quick().await(request).use { response ->
        if (!response.isSuccessful) {
            throw SkillInstallFailure("GitHub couldn't download one of the skill's files.")
        }
        return response.body?.byteStream()?.readCapped(remaining) ?: ByteArray(0)
    }
}

private fun githubRequest(url: HttpUrl): Request =
    Request.Builder().url(url).header("Accept", GITHUB_ACCEPT).header("User-Agent", "Rivet").build()

private fun contentsUrl(source: GitHubSource, path: String): HttpUrl {
    val builder = "$GITHUB_API/repos/${source.owner}/${source.repo}/contents".toHttpUrl().newBuilder()
    path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
    source.branch?.let { builder.addQueryParameter("ref", it) }
    return builder.build()
}

private fun githubFailure(response: Response): SkillInstallFailure = when (response.code) {
    404 -> SkillInstallFailure("That GitHub link doesn't exist, or the repo is private.")
    403, 429 -> SkillInstallFailure("GitHub is rate-limiting Rivet. Try again in a few minutes.")
    else -> SkillInstallFailure("GitHub couldn't be read (HTTP ${response.code}).")
}

private fun parseGitHubEntries(body: String): List<GitHubEntry> {
    val element = try {
        Json.parseToJsonElement(body)
    } catch (e: IllegalArgumentException) {
        throw SkillInstallFailure("GitHub returned something Rivet couldn't read.")
    }
    return when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }.mapNotNull(::gitHubEntry)
        is JsonObject -> listOfNotNull(gitHubEntry(element))
        else -> emptyList()
    }
}

private fun gitHubEntry(obj: JsonObject): GitHubEntry? {
    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return null
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
    val download = (obj["download_url"] as? JsonPrimitive)?.contentOrNull
    return GitHubEntry(name, path, type, download)
}

// ---------------------------------------------------------------- shared validation

private fun validateSkillPayload(
    files: List<BundledFile>,
    folder: String?,
    sourceLabel: String,
): SkillPayload {
    val markdown = files.firstOrNull { it.path == "SKILL.md" }?.bytes?.toString(Charsets.UTF_8)
        ?: throw SkillInstallFailure(NO_SKILL_MD)
    val skill = when (val parsed = parseSkill(markdown)) {
        is SkillParseResult.Parsed -> parsed.skill
        is SkillParseResult.Invalid -> throw SkillInstallFailure(
            "This skill can't be installed: ${parsed.reason}.")
    }
    if (folderNameMismatch(folder, skill.name)) {
        throw SkillInstallFailure(
            "The folder is named $folder but this skill declares ${skill.name}.")
    }
    return SkillPayload(skill, files, sourceLabel)
}

/** Reads at most [remaining] bytes; one byte past it is the over-cap failure. */
private fun InputStream.readCapped(remaining: Long): ByteArray {
    if (remaining <= 0) throw SkillInstallFailure(TOO_BIG)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        if (output.size() > remaining) throw SkillInstallFailure(TOO_BIG)
    }
    return output.toByteArray()
}
