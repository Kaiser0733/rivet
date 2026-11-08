package com.kaiser.rivet.agent

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SKILL_FILE_NAME = "SKILL.md"
private const val MAX_SKILL_BYTES = 256L * 1024

/** dsh's precedence: the lowest rank wins a name conflict. */
internal fun SkillRootKind.rank(): Int = when (this) {
    SkillRootKind.ProjectRivet -> 100
    SkillRootKind.ProjectAgents -> 200
    SkillRootKind.Global -> 400
    SkillRootKind.Bundled -> 600
}

internal data class LocatedSkill(val skill: Skill, val rank: Int, val path: String)

/**
 * Discovers installed skills from the three roots, in dsh's rank order: project
 * `.rivet/skills` (100), project `.agents/skills` (200), then the app-global `skills/`
 * under filesDir (400). A root holds directory skills (`<name>/SKILL.md`) and flat files
 * (`<name>.md`).
 *
 * This must never run on the chat send path: [catalog] serves a per-workspace cache and
 * only [refresh] (or opening a different workspace) re-scans. Both are suspending and do
 * their IO off the main thread.
 */
class SkillDiscovery(private val globalRoot: File) {
    private val mutex = Mutex()
    private var cached: CachedCatalog? = null

    suspend fun catalog(workspace: AgentWorkspace?, workspaceId: String): SkillCatalog =
        mutex.withLock {
            cached?.takeIf { it.workspaceId == workspaceId }?.catalog
                ?: scan(workspace).also { cached = CachedCatalog(workspaceId, it) }
        }

    suspend fun refresh(workspace: AgentWorkspace?, workspaceId: String): SkillCatalog =
        mutex.withLock {
            scan(workspace).also { cached = CachedCatalog(workspaceId, it) }
        }

    private suspend fun scan(workspace: AgentWorkspace?): SkillCatalog = withContext(Dispatchers.IO) {
        val located = mutableListOf<LocatedSkill>()
        val errors = mutableListOf<SkillError>()
        if (workspace != null) {
            located += scanWorkspace(".rivet/skills", SkillRootKind.ProjectRivet, errors,
                list = workspace::list, read = workspace::read)
            located += scanWorkspace(".agents/skills", SkillRootKind.ProjectAgents, errors,
                list = workspace::list, read = workspace::read)
        }
        located += scanDirectory(globalRoot, SkillRootKind.Global, errors)
        catalogOf(located, errors)
    }

    private data class CachedCatalog(val workspaceId: String, val catalog: SkillCatalog)
}

/** Scan one SAF root. A missing root is normal and yields nothing. */
internal suspend fun scanWorkspace(
    root: String,
    kind: SkillRootKind,
    errors: MutableList<SkillError>,
    list: suspend (String) -> List<AgentWorkspaceEntry>,
    read: suspend (String) -> AgentFileSnapshot,
): List<LocatedSkill> {
    val children = try {
        list(root)
    } catch (_: AgentWorkspaceFailure) {
        return emptyList()
    }
    val located = mutableListOf<LocatedSkill>()
    for (entry in children.sortedBy { it.path }) {
        if (entry.directory) {
            val folder = entry.path.substringAfterLast('/')
            if (folder.isEmpty()) continue
            val skillFile = try {
                list(entry.path).firstOrNull {
                    !it.directory && it.path.substringAfterLast('/') == SKILL_FILE_NAME
                }
            } catch (_: AgentWorkspaceFailure) {
                null
            } ?: continue
            loadWorkspaceSkill(folder, skillFile.path, entry.path, kind, errors, read)
                ?.let { located += it }
        } else if (entry.path.endsWith(".md")) {
            val stem = entry.path.substringAfterLast('/').removeSuffix(".md")
            if (stem.isEmpty()) continue
            loadWorkspaceSkill(stem, entry.path, root, kind, errors, read)?.let { located += it }
        }
    }
    return located
}

private suspend fun loadWorkspaceSkill(
    expectedName: String,
    path: String,
    baseDir: String,
    kind: SkillRootKind,
    errors: MutableList<SkillError>,
    read: suspend (String) -> AgentFileSnapshot,
): LocatedSkill? {
    val snapshot = try {
        read(path)
    } catch (failure: AgentWorkspaceFailure) {
        errors += SkillError(expectedName, path, "unreadable (${failure.code})")
        return null
    }
    if (snapshot.size > MAX_SKILL_BYTES) {
        errors += SkillError(expectedName, path, "larger than ${MAX_SKILL_BYTES / 1024} KB")
        return null
    }
    return acceptSkill(expectedName, path, baseDir, kind, snapshot.text, errors)
}

/** Scan one java.io.File root, shaped like [scanWorkspace]. */
internal fun scanDirectory(
    root: File,
    kind: SkillRootKind,
    errors: MutableList<SkillError>,
): List<LocatedSkill> {
    val children = root.listFiles()?.sortedBy { it.name } ?: return emptyList()
    val located = mutableListOf<LocatedSkill>()
    for (child in children) {
        when {
            child.isDirectory -> {
                val skillFile = File(child, SKILL_FILE_NAME)
                if (!skillFile.isFile) continue
                loadFileSkill(skillFile, child.name, child.absolutePath, kind, errors)
                    ?.let { located += it }
            }
            child.isFile && child.name.endsWith(".md") -> {
                val stem = child.name.removeSuffix(".md")
                if (stem.isEmpty()) continue
                loadFileSkill(child, stem, root.absolutePath, kind, errors)?.let { located += it }
            }
        }
    }
    return located
}

private fun loadFileSkill(
    file: File,
    expectedName: String,
    baseDir: String,
    kind: SkillRootKind,
    errors: MutableList<SkillError>,
): LocatedSkill? {
    if (file.length() > MAX_SKILL_BYTES) {
        errors += SkillError(expectedName, file.absolutePath, "larger than ${MAX_SKILL_BYTES / 1024} KB")
        return null
    }
    val text = try {
        file.readText()
    } catch (_: IOException) {
        errors += SkillError(expectedName, file.absolutePath, "unreadable")
        return null
    }
    return acceptSkill(expectedName, file.absolutePath, baseDir, kind, text, errors)
}

private fun acceptSkill(
    expectedName: String,
    path: String,
    baseDir: String,
    kind: SkillRootKind,
    text: String,
    errors: MutableList<SkillError>,
): LocatedSkill? = when (val parsed = parseSkill(text)) {
    is SkillParseResult.Invalid -> {
        errors += SkillError(expectedName, path, parsed.reason)
        null
    }
    is SkillParseResult.Parsed -> {
        val skill = parsed.skill
        if (skill.name != expectedName) {
            errors += SkillError(expectedName, path,
                "name \"${skill.name}\" must match \"$expectedName\"")
            null
        } else {
            LocatedSkill(skill.copy(source = SkillSource(baseDir, kind)), kind.rank(), path)
        }
    }
}

/**
 * Resolve conflicts and freeze the catalog. Entries are taken lowest rank first, in the
 * scan's name order, so the winner never depends on map iteration. Cross-root shadowing is the intended
 * behaviour and stays silent; two skills with one name in the *same* root are ambiguous
 * and the loser becomes an error entry.
 */
internal fun catalogOf(located: List<LocatedSkill>, errors: List<SkillError>): SkillCatalog {
    val winners = LinkedHashMap<String, LocatedSkill>()
    val conflicts = mutableListOf<SkillError>()
    // Stable sort by rank only: within one root the scan already visited entries in
    // name order, so the winner never depends on map/scan iteration order.
    for (entry in located.sortedBy { it.rank }) {
        val current = winners[entry.skill.name]
        when {
            current == null -> winners[entry.skill.name] = entry
            current.rank == entry.rank -> conflicts += SkillError(
                displayName = entry.skill.name,
                path = entry.path,
                reason = "duplicate name; using ${current.path}",
            )
        }
    }
    val skills = winners.values.map { it.skill }.sortedBy { it.name }
    val allErrors = (errors + conflicts).sortedWith(compareBy({ it.path }, { it.displayName }))
    return SkillCatalog(skills, allErrors, skillCatalogDigest(skills))
}
