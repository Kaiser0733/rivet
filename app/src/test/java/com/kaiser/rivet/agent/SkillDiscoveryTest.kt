package com.kaiser.rivet.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillDiscoveryTest {
    @get:Rule val temp = TemporaryFolder()

    // In-memory SAF tree: null value = directory, text = file.
    private val nodes = linkedMapOf<String, String?>()

    @Before fun resetTree() = nodes.clear()

    private fun skillText(name: String, description: String = "Use $name when asked.") =
        "---\nname: $name\ndescription: $description\n---\n# $name\n\nBody."

    private fun directory(path: String) { nodes[path] = null }

    private fun file(path: String, name: String, description: String = "Use $name when asked.") {
        nodes[path] = skillText(name, description)
    }

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")

    private suspend fun scan(
        root: String,
        kind: SkillRootKind,
        errors: MutableList<SkillError>,
    ): List<LocatedSkill> = scanWorkspace(root, kind, errors,
        list = { path ->
            nodes.entries.filter { parentOf(it.key) == path }
                .map { AgentWorkspaceEntry(it.key, it.value == null, it.value?.length?.toLong()) }
        },
        read = { path ->
            val text = nodes[path] ?: throw AgentWorkspaceFailure("missing")
            AgentFileSnapshot(path, text, "", text.length.toLong())
        })

    private fun workspaceCatalog(vararg roots: Pair<String, SkillRootKind>): SkillCatalog = runBlocking {
        val errors = mutableListOf<SkillError>()
        val located = mutableListOf<LocatedSkill>()
        roots.forEach { (root, kind) -> located += scan(root, kind, errors) }
        catalogOf(located, errors)
    }

    private fun located(name: String, description: String, kind: SkillRootKind, path: String) =
        LocatedSkill(
            Skill(name = name, description = description, body = "Body",
                source = SkillSource(path, kind)),
            kind.rank(), path)

    private fun writeGlobal(root: File, name: String, description: String = "Use $name when asked.") {
        val folder = File(root, name)
        folder.mkdirs()
        File(folder, "SKILL.md").writeText(skillText(name, description))
    }

    @Test fun projectRivetRootShadowsProjectAgentsRoot() {
        directory(".rivet/skills/notes")
        file(".rivet/skills/notes/SKILL.md", "notes")
        directory(".agents/skills/notes")
        file(".agents/skills/notes/SKILL.md", "notes")

        val catalog = workspaceCatalog(
            ".rivet/skills" to SkillRootKind.ProjectRivet,
            ".agents/skills" to SkillRootKind.ProjectAgents)

        assertEquals(listOf("notes"), catalog.skills.map { it.name })
        assertEquals(SkillRootKind.ProjectRivet, catalog.skills.single().source!!.kind)
        assertEquals(".rivet/skills/notes", catalog.skills.single().source!!.baseDir)
        assertTrue(catalog.errors.isEmpty())
    }

    @Test fun projectRootShadowsGlobalRoot() {
        directory(".rivet/skills/notes")
        file(".rivet/skills/notes/SKILL.md", "notes")
        val global = temp.newFolder("skills")
        writeGlobal(global, "notes")

        val catalog = runBlocking {
            val errors = mutableListOf<SkillError>()
            val located = scan(".rivet/skills", SkillRootKind.ProjectRivet, errors) +
                scanDirectory(global, SkillRootKind.Global, errors)
            catalogOf(located, errors)
        }

        assertEquals(1, catalog.skills.size)
        assertEquals(SkillRootKind.ProjectRivet, catalog.skills.single().source!!.kind)
        assertTrue(catalog.errors.isEmpty())
    }

    @Test fun folderNameMismatchBecomesAnError() {
        directory(".rivet/skills/notes")
        file(".rivet/skills/notes/SKILL.md", "other")

        val catalog = workspaceCatalog(".rivet/skills" to SkillRootKind.ProjectRivet)

        assertTrue(catalog.skills.isEmpty())
        assertEquals(1, catalog.errors.size)
        assertEquals(".rivet/skills/notes/SKILL.md", catalog.errors.single().path)
        assertTrue(catalog.errors.single().reason.contains("must match"))
    }

    @Test fun invalidSkillCarriesTheParserReason() {
        directory(".rivet/skills/broken")
        nodes[".rivet/skills/broken/SKILL.md"] = "---\nname: broken\n---\nBody"

        val catalog = workspaceCatalog(".rivet/skills" to SkillRootKind.ProjectRivet)

        assertTrue(catalog.skills.isEmpty())
        assertTrue(catalog.errors.single().reason.contains("description"))
    }

    @Test fun flatFileSkillLoads() {
        file(".rivet/skills/quick.md", "quick")

        val catalog = workspaceCatalog(".rivet/skills" to SkillRootKind.ProjectRivet)

        assertEquals(listOf("quick"), catalog.skills.map { it.name })
        assertEquals(".rivet/skills", catalog.skills.single().source!!.baseDir)
        assertTrue(catalog.errors.isEmpty())
    }

    @Test fun flatFileStemMustMatchTheName() {
        file(".rivet/skills/quick.md", "slow")

        val catalog = workspaceCatalog(".rivet/skills" to SkillRootKind.ProjectRivet)

        assertTrue(catalog.skills.isEmpty())
        assertTrue(catalog.errors.single().reason.contains("must match"))
    }

    @Test fun sameRootDuplicateBecomesAnError() {
        directory(".rivet/skills/notes")
        file(".rivet/skills/notes/SKILL.md", "notes")
        file(".rivet/skills/notes.md", "notes")

        val catalog = workspaceCatalog(".rivet/skills" to SkillRootKind.ProjectRivet)

        assertEquals(listOf("notes"), catalog.skills.map { it.name })
        assertTrue(catalog.skills.single().source!!.baseDir.endsWith("/notes"))
        assertEquals(1, catalog.errors.size)
        assertTrue(catalog.errors.single().reason.contains("duplicate"))
    }

    @Test fun globalRootLoadsDirectoriesAndFlatFiles() {
        val root = temp.newFolder("skills")
        writeGlobal(root, "release-notes")
        File(root, "explain.md").writeText(skillText("explain"))

        val errors = mutableListOf<SkillError>()
        val catalog = catalogOf(scanDirectory(root, SkillRootKind.Global, errors), errors)

        assertEquals(listOf("explain", "release-notes"), catalog.skills.map { it.name })
        assertTrue(catalog.skills.all { it.source!!.kind == SkillRootKind.Global })
        assertTrue(catalog.errors.isEmpty())
    }

    @Test fun emptyRootsYieldAnEmptyCatalog() {
        directory(".rivet/skills/not-a-skill")
        nodes[".rivet/skills/not-a-skill/readme.txt"] = "not a skill"
        val project = workspaceCatalog(".rivet/skills" to SkillRootKind.ProjectRivet)
        assertTrue(project.isEmpty)
        assertTrue(project.errors.isEmpty())

        val root = temp.newFolder("skills")
        val global = runBlocking { SkillDiscovery(root).catalog(null, "") }
        assertTrue(global.isEmpty)
        assertTrue(global.errors.isEmpty())
        assertEquals(64, global.digest.length)
        assertEquals(skillCatalogDigest(emptyList()), global.digest)
    }

    @Test fun digestIsStableAndTracksDescriptions() {
        val one = located("demo", "First description.", SkillRootKind.ProjectRivet, ".rivet/skills/demo")
        val two = located("other", "Other description.", SkillRootKind.ProjectAgents, ".agents/skills/other")

        val forward = catalogOf(listOf(one, two), emptyList()).digest
        val reversed = catalogOf(listOf(two, one), emptyList()).digest
        val changed = catalogOf(
            listOf(one.copy(skill = one.skill.copy(description = "Second description.")), two),
            emptyList()).digest

        assertEquals(forward, reversed)
        assertNotEquals(forward, changed)
        assertEquals(64, forward.length)
    }

    @Test fun errorEntriesDoNotChangeTheDigest() {
        val one = located("demo", "Description.", SkillRootKind.ProjectRivet, ".rivet/skills/demo")
        val withoutErrors = catalogOf(listOf(one), emptyList())
        val withErrors = catalogOf(listOf(one), listOf(SkillError("broken", ".rivet/skills/broken", "missing description")))
        assertEquals(withoutErrors.digest, withErrors.digest)
        assertNotEquals(withoutErrors, withErrors)
    }

    @Test fun catalogIsCachedUntilRefresh() {
        val root = temp.newFolder("skills")
        writeGlobal(root, "demo", "First.")
        val discovery = SkillDiscovery(root)

        val first = runBlocking { discovery.catalog(null, "") }
        assertEquals("First.", first.skills.single().description)

        writeGlobal(root, "demo", "Second.")
        val cached = runBlocking { discovery.catalog(null, "") }
        assertEquals("First.", cached.skills.single().description)

        val refreshed = runBlocking { discovery.refresh(null, "") }
        assertEquals("Second.", refreshed.skills.single().description)
        assertNotEquals(first.digest, refreshed.digest)

        writeGlobal(root, "demo", "Third.")
        val otherWorkspace = runBlocking { discovery.catalog(null, "other-workspace") }
        assertEquals("Third.", otherWorkspace.skills.single().description)
    }

    @Test fun catalogWithNullWorkspaceDoesNotPoisonSubsequentWorkspaceCatalog() {
        val root = temp.newFolder("skills")
        writeGlobal(root, "global-skill", "Global.")
        directory(".rivet/skills/project-skill")
        file(".rivet/skills/project-skill/SKILL.md", "project-skill", "Project.")
        val discovery = SkillDiscovery(root)

        val globalOnly = runBlocking { discovery.catalog(null, "ws-1") }
        assertEquals(listOf("global-skill"), globalOnly.skills.map { it.name })

        val workspace = object : AgentWorkspace {
            override val identity: String = "ws-1"
            override suspend fun list(path: String): List<AgentWorkspaceEntry> =
                nodes.entries.filter { parentOf(it.key) == path }
                    .map { AgentWorkspaceEntry(it.key, it.value == null, it.value?.length?.toLong()) }
            override suspend fun read(path: String): AgentFileSnapshot {
                val text = nodes[path] ?: throw AgentWorkspaceFailure("missing")
                return AgentFileSnapshot(path, text, "", text.length.toLong())
            }
            override suspend fun exists(path: String): Boolean = path in nodes
            override suspend fun isDirectory(path: String): Boolean = nodes[path] == null && path in nodes
            override suspend fun write(path: String, content: String): AgentFileSnapshot = throw UnsupportedOperationException()
            override suspend fun createDirectory(path: String) = throw UnsupportedOperationException()
            override suspend fun delete(path: String) = throw UnsupportedOperationException()
            override suspend fun rename(path: String, newName: String) = throw UnsupportedOperationException()
            override suspend fun move(source: String, destDir: String) = throw UnsupportedOperationException()
            override suspend fun copy(source: String, dest: String) = throw UnsupportedOperationException()
            override suspend fun diff(path: String): String = ""
        }

        val withProject = runBlocking { discovery.catalog(workspace, "ws-1") }
        assertEquals(listOf("project-skill", "global-skill"), withProject.skills.map { it.name })
    }
}
