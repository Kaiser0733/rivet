package com.kaiser.rivet.agent

import java.io.File

/** Largest skill Rivet will install, measured on the unpacked bytes. */
internal const val MAX_SKILL_INSTALL_BYTES = 20L * 1024 * 1024

/** A skill folder Rivet refuses to install because it would write too many files. */
internal const val MAX_SKILL_FILES = 512

/** The app-global skills root; ChatViewModel's discovery uses the same folder. */
internal fun globalSkillsRoot(filesDir: File): File = File(filesDir, "skills")

internal data class GitHubSource(
    val owner: String,
    val repo: String,
    val branch: String?,
    val path: String,
) {
    val label: String
        get() = buildString {
            append("github.com/").append(owner).append('/').append(repo)
            if (path.isNotEmpty()) append('/').append(path)
        }
}

/** A skill folder found in a GitHub listing or a zip, as a path relative to its source. */
internal data class SkillCandidate(val path: String)

private val GITHUB_LINK = Regex(
    """^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(/.*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Reads a public GitHub link: the repo itself, or `/tree/<branch>` with an optional folder.
 * Anything else (another host, a blob link, a lone owner) is null.
 */
internal fun parseGitHubLink(raw: String): GitHubSource? {
    val match = GITHUB_LINK.find(raw.trim()) ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2].removeSuffix(".git")
    if (owner == "." || owner == ".." || repo.isEmpty() || repo == "." || repo == "..") return null
    val rest = match.groupValues[3].trim('/')
    if (rest.isEmpty()) return GitHubSource(owner, repo, null, "")
    val parts = rest.split('/')
    if (parts.size < 2 || !parts[0].equals("tree", ignoreCase = true) || parts[1].isEmpty()) {
        return null
    }
    // ponytail: a branch containing "/" is read as its first segment. Tree names with
    // slashes need the git/trees API; revisit only if users actually link those.
    val path = parts.drop(2).joinToString("/").trim('/')
    return GitHubSource(owner, repo, parts[1], path)
}

/** True when [name] is a relative path that cannot escape the skill folder (zip-slip). */
internal fun zipEntryIsSafe(name: String): Boolean {
    if (name.isEmpty() || name.startsWith('/') || name.startsWith('\\')) return false
    if (name.any { it == '\\' || it == '\u0000' }) return false
    // Windows drive letters are not relative paths.
    if (name.hasDrivePrefix()) return false
    return name.split('/').none { it == ".." || it.isEmpty() }
}

private fun String.hasDrivePrefix(): Boolean = length > 1 && this[1] == ':'

/** Entries every platform adds that must never become skill files. */
internal fun isZipJunk(name: String): Boolean {
    val base = name.substringAfterLast('/')
    return name == "__MACOSX" || name.startsWith("__MACOSX/") ||
        base == ".DS_Store" || base.startsWith("._")
}

/**
 * The skill folders in a zip listing, as prefixes ending in "/" (or "" for a zip whose
 * own root holds SKILL.md). A root-level SKILL.md wins: the zip itself is one skill.
 * ponytail: a nested SKILL.md deeper than one level is still found because every entry
 * path is inspected; there is no depth limit to tune.
 */
internal fun zipSkillRoots(entries: List<String>): List<String> {
    val files = entries.filter { it.isNotEmpty() && !it.endsWith('/') && !isZipJunk(it) }
    if ("SKILL.md" in files) return listOf("")
    return files.asSequence()
        .filter { it.endsWith("/SKILL.md") }
        .map { it.removeSuffix("SKILL.md") }
        .distinct()
        .sorted()
        .toList()
}

/** The last folder in a skill path, or null when the path is a source root. */
internal fun installFolderName(path: String): String? {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) null else trimmed.substringAfterLast('/')
}

/** The installed folder must agree with the name the skill declares. */
internal fun folderNameMismatch(folder: String?, skillName: String): Boolean =
    folder != null && folder != skillName

internal fun exceedsInstallCap(bytes: Long): Boolean = bytes > MAX_SKILL_INSTALL_BYTES

/** True when `skills/<name>` already exists and installing would replace it. */
internal fun installConflict(globalRoot: File, name: String): Boolean =
    File(globalRoot, name).exists()

private val SCRIPT_EXTENSIONS =
    setOf("sh", "bash", "zsh", "py", "js", "ts", "rb", "pl", "ps1", "bat", "cmd")

/** Scripts are the risk surface of a shared skill, so the review names them plainly. */
internal fun isBundledScript(path: String): Boolean =
    path.startsWith("scripts/") ||
        path.substringAfterLast('.', "").lowercase() in SCRIPT_EXTENSIONS

internal class BundledFile(val path: String, val bytes: ByteArray)

internal class SkillPayload(
    val skill: Skill,
    val files: List<BundledFile>,
    val sourceLabel: String,
) {
    /** Everything except SKILL.md itself, sorted for the review list. */
    val bundledPaths: List<String>
        get() = files.map { it.path }.filter { it != "SKILL.md" }.sorted()
}

/** A plain, user-facing install failure; [message] is shown verbatim. */
internal class SkillInstallFailure(message: String) : Exception(message)
