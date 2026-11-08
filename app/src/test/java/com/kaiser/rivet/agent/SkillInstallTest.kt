package com.kaiser.rivet.agent

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillInstallTest {
    @get:Rule val temp = TemporaryFolder()

    private val notesMarkdown = """
        ---
        name: notes
        description: Use notes when the user asks for a summary.
        ---
        Write the notes down.
    """.trimIndent()

    // ------------------------------------------------------------ GitHub links

    @Test fun repoLinkParses() {
        assertEquals(GitHubSource("o", "r", null, ""), parseGitHubLink("https://github.com/o/r"))
    }

    @Test fun repoLinkVariantsParse() {
        assertEquals(GitHubSource("o", "r", null, ""), parseGitHubLink("https://github.com/o/r/"))
        assertEquals(GitHubSource("o", "r", null, ""), parseGitHubLink("http://www.github.com/o/r.git"))
        assertEquals(GitHubSource("o", "r", null, ""), parseGitHubLink("github.com/o/r"))
    }

    @Test fun treeLinkParses() {
        assertEquals(GitHubSource("o", "r", "main", "skills/notes"),
            parseGitHubLink("https://github.com/o/r/tree/main/skills/notes"))
        assertEquals(GitHubSource("o", "r", "main", ""),
            parseGitHubLink("https://github.com/o/r/tree/main/"))
    }

    @Test fun junkLinksAreRejected() {
        listOf(
            "",
            "not a link",
            "https://gitlab.com/o/r",
            "https://github.com/o",
            "https://github.com/o/r/blob/main/SKILL.md",
            "https://github.com/o/r?tab=readme",
        ).forEach { assertNull(it, parseGitHubLink(it)) }
    }

    // ------------------------------------------------------------ zip paths

    @Test fun nestedZipPathsAreAccepted() {
        assertTrue(zipEntryIsSafe("SKILL.md"))
        assertTrue(zipEntryIsSafe("scripts/run.sh"))
        assertTrue(zipEntryIsSafe("notes/references/spec.md"))
    }

    @Test fun escapingZipPathsAreRejected() {
        listOf(
            "/etc/passwd",
            "../evil.sh",
            "notes/../../evil.sh",
            "notes/../evil.sh",
            "..\\evil.sh",
            "C:/evil.sh",
            "notes//SKILL.md",
            "",
        ).forEach { assertFalse(it, zipEntryIsSafe(it)) }
    }

    @Test fun sizeCapIsTwentyMegabytes() {
        assertFalse(exceedsInstallCap(MAX_SKILL_INSTALL_BYTES))
        assertTrue(exceedsInstallCap(MAX_SKILL_INSTALL_BYTES + 1))
    }

    // ------------------------------------------------------------ zip discovery

    @Test fun rootSkillMdMakesTheZipOneSkill() {
        assertEquals(listOf(""), zipSkillRoots(listOf(
            "SKILL.md", "scripts/run.sh", "skills/other/SKILL.md")))
    }

    @Test fun nestedSkillFoldersAreFound() {
        assertEquals(listOf("skills/notes/", "skills/other/"), zipSkillRoots(listOf(
            "skills/notes/SKILL.md",
            "skills/notes/scripts/run.sh",
            "skills/other/SKILL.md",
        )))
    }

    @Test fun zipWithoutSkillMdHasNoRoots() {
        assertTrue(zipSkillRoots(listOf("readme.md", "skills/notes/notes.md")).isEmpty())
    }

    @Test fun macOSJunkIsIgnored() {
        assertTrue(zipSkillRoots(listOf(
            "__MACOSX/skills/notes/._SKILL.md", "skills/notes/.DS_Store")).isEmpty())
    }

    // ------------------------------------------------------------ folder + conflict

    @Test fun folderNameMustMatchTheDeclaredName() {
        assertEquals("notes", installFolderName("skills/notes/"))
        assertNull(installFolderName(""))
        assertNull(installFolderName("/"))
        assertTrue(folderNameMismatch("notes", "other"))
        assertFalse(folderNameMismatch(null, "notes"))
        assertFalse(folderNameMismatch("notes", "notes"))
    }

    @Test fun existingGlobalFolderIsAConflict() {
        val root = temp.newFolder("skills")
        assertFalse(installConflict(root, "notes"))
        File(root, "notes").mkdirs()
        assertTrue(installConflict(root, "notes"))
    }

    // ------------------------------------------------------------ zip round trip

    private fun zipOf(name: String, entries: List<Pair<String, String>>): File {
        val zip = temp.newFile(name)
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test fun zipSkillIsReadParsedAndListed() = runBlocking {
        val zip = zipOf("notes.zip", listOf(
            "notes/SKILL.md" to notesMarkdown,
            "notes/scripts/run.sh" to "echo hi\n",
            "notes/references/spec.md" to "spec",
        ))
        val roots = zipSkillCandidates(zip)

        assertEquals(listOf("notes/"), roots)
        val payload = readZipSkill(zip, roots[0], "notes.zip")
        assertEquals("notes", payload.skill.name)
        assertEquals(listOf("references/spec.md", "scripts/run.sh"), payload.bundledPaths)
        assertTrue(isBundledScript("scripts/run.sh"))
        assertFalse(isBundledScript("references/spec.md"))
    }

    @Test fun zipSlipEntryAbortsTheRead() = runBlocking {
        val zip = zipOf("evil.zip", listOf(
            "notes/SKILL.md" to notesMarkdown,
            "notes/../evil.sh" to "rm -rf",
        ))

        val error = assertThrows(SkillInstallFailure::class.java) {
            runBlocking { readZipSkill(zip, "notes/", "evil.zip") }
        }
        assertEquals("This skill contains a file path Rivet won't write.", error.message)
    }

    @Test fun folderNameMismatchAbortsTheRead() = runBlocking {
        val zip = zipOf("wrong.zip", listOf("other/SKILL.md" to notesMarkdown))

        val error = assertThrows(SkillInstallFailure::class.java) {
            runBlocking { readZipSkill(zip, "other/", "wrong.zip") }
        }
        assertTrue(error.message.orEmpty().contains("The folder is named other"))
    }

    // ------------------------------------------------------------ install write

    @Test fun installWritesAndReplacesTheFolder() = runBlocking {
        val root = temp.newFolder("skills")
        val skill = (parseSkill(notesMarkdown) as SkillParseResult.Parsed).skill
        installSkill(SkillPayload(skill,
            listOf(BundledFile("SKILL.md", notesMarkdown.toByteArray()),
                BundledFile("scripts/run.sh", "echo hi\n".toByteArray())), "notes.zip"), root)

        val installed = File(root, "notes")
        assertTrue(File(installed, "SKILL.md").isFile)
        assertEquals("echo hi\n", File(installed, "scripts/run.sh").readText())
        assertFalse(File(root, ".notes.installing").exists())

        val replaced = notesMarkdown.replace("Write the notes down.", "Write the notes twice.")
        installSkill(SkillPayload(skill,
            listOf(BundledFile("SKILL.md", replaced.toByteArray())), "notes.zip"), root)

        assertEquals(replaced, File(installed, "SKILL.md").readText())
        assertFalse(File(installed, "scripts/run.sh").exists())
    }
}
