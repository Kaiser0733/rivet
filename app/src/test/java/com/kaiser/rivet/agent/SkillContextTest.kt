package com.kaiser.rivet.agent

import org.junit.Assert.*
import org.junit.Test

class SkillContextTest {
    private fun skill(
        name: String,
        description: String = "Use $name when asked.",
        disableModelInvocation: Boolean = false,
        baseDir: String = ".rivet/skills/$name",
    ) = Skill(name = name, description = description, body = "Body of $name.",
        disableModelInvocation = disableModelInvocation,
        source = SkillSource(baseDir, SkillRootKind.ProjectRivet))

    private fun catalog(vararg skills: Skill): SkillCatalog =
        SkillCatalog(skills.toList(), emptyList(), skillCatalogDigest(skills.toList()))

    @Test fun listingHasOneLinePerModelInvocableSkill() {
        val text = SkillContext.listing(catalog(skill("notes"), skill("explain-error")))

        assertTrue(text.startsWith("<available_skills>\n"))
        assertTrue(text.contains("- `explain-error`: Use explain-error when asked.\n"))
        assertTrue(text.contains("- `notes`: Use notes when asked.\n"))
        assertTrue(text.contains("</available_skills>"))
        assertTrue(text.contains("call the `skill` tool"))
    }

    @Test fun manualOnlySkillsAreNotListed() {
        val text = SkillContext.listing(catalog(
            skill("notes"),
            skill("manual", disableModelInvocation = true)))

        assertTrue(text.contains("`notes`"))
        assertFalse(text.contains("manual"))
    }

    @Test fun descriptionsAreCappedAt500Characters() {
        val text = SkillContext.listing(catalog(skill("long", "d".repeat(600))))
        val line = text.lines().first { it.startsWith("- `long`") }
        assertEquals(500, line.removePrefix("- `long`: ").length)
        assertTrue(line.endsWith("..."))
    }

    @Test fun emptyCatalogProducesNoSnapshot() {
        assertTrue(SkillContext.listing(null).isEmpty())
        assertTrue(SkillContext.listing(catalog()).isEmpty())
        assertTrue(SkillContext.updates(emptyList(), "ws", catalog()).isEmpty())
        assertTrue(SkillContext.updates(emptyList(), "ws", catalog(skill("manual", disableModelInvocation = true))).isEmpty())
    }

    @Test fun snapshotIsPublishedOnceAndOnlyWhenTheListingChanges() {
        val first = SkillContext.updates(emptyList(), "ws", catalog(skill("notes")))
        assertEquals(1, first.size)
        val entry = first.single().validatedContext()
        assertEquals(SKILL_CATALOG_KIND, entry?.kind)
        assertEquals("ws", entry?.workspaceId)

        assertTrue(SkillContext.updates(first, "ws", catalog(skill("notes"))).isEmpty())
        // A manual-only skill is not in the listing, so it cannot republish it.
        assertTrue(SkillContext.updates(first, "ws",
            catalog(skill("notes"), skill("manual", disableModelInvocation = true))).isEmpty())
        assertEquals(1, SkillContext.updates(first, "ws",
            catalog(skill("notes", description = "Write release notes."))).size)
    }

    @Test fun onlyTheNewestCatalogReachesTheModel() {
        val first = SkillContext.updates(emptyList(), "ws", catalog(skill("notes", "Old words.")))
        val second = SkillContext.updates(first, "ws", catalog(skill("notes", "New words.")))
        val messages = listOf(AgentMessage.user("Do the thing")) + first + second

        val model = modelMessages(messages, "ws", enforceWorkspace = true)
        val catalogs = model.filter { it.text.contains("<available_skills>") }
        assertEquals(1, catalogs.size)
        assertTrue(catalogs.single().text.contains("New words."))
        assertFalse(catalogs.single().text.contains("Old words."))
    }

    @Test fun oversizedCatalogIsTruncatedBelowTheContextLimit() {
        val many = (1..200).map { skill("skill-$it", "d".repeat(500)) }
        val text = SkillContext.listing(catalog(*many.toTypedArray()))

        assertTrue(text.toByteArray(Charsets.UTF_8).size <= InternalContext.MAX_CONTENT_BYTES)
        assertTrue(text.contains("more installed skills are not listed"))
    }
}
