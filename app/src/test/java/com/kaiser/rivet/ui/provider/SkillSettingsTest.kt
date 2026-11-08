package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.SkillCatalog
import com.kaiser.rivet.agent.SkillError
import com.kaiser.rivet.agent.SkillRootKind
import com.kaiser.rivet.agent.SkillSource
import com.kaiser.rivet.agent.skillCatalogDigest
import org.junit.Assert.*
import org.junit.Test

class SkillSettingsTest {
    private fun baseDir(kind: SkillRootKind, name: String): String = when (kind) {
        SkillRootKind.ProjectRivet -> ".rivet/skills/$name"
        SkillRootKind.ProjectAgents -> ".agents/skills/$name"
        SkillRootKind.Global -> "/data/rivet/skills/$name"
        SkillRootKind.Bundled -> "/bundled/$name"
    }

    private fun skill(name: String, kind: SkillRootKind, disableModelInvocation: Boolean = false) =
        Skill(name = name, description = "Use $name when asked.", body = "Body.",
            disableModelInvocation = disableModelInvocation,
            source = SkillSource(baseDir(kind, name), kind))

    private fun error(path: String = ".rivet/skills/broken/SKILL.md") =
        SkillError("broken", path, "missing description")

    private fun catalog(skills: List<Skill>, errors: List<SkillError> = emptyList()) =
        SkillCatalog(skills, errors, skillCatalogDigest(skills))

    @Test fun groupsFollowRootRankAndSkipEmptyRoots() {
        val sections = skillPageSections(catalog(listOf(
            skill("g1", SkillRootKind.Global),
            skill("p1", SkillRootKind.ProjectRivet),
            skill("a1", SkillRootKind.ProjectAgents),
        )))
        val labels = sections.filterIsInstance<SkillPageSection.Skills>().map { it.label }

        assertEquals(listOf("This project", "Shared (.agents)", "All projects"), labels)
        assertEquals(listOf("p1"),
            (sections[0] as SkillPageSection.Skills).skills.map { it.name })
    }

    @Test fun errorGroupComesLast() {
        val sections = skillPageSections(catalog(
            listOf(skill("p1", SkillRootKind.ProjectRivet)), listOf(error())))

        assertTrue(sections.last() is SkillPageSection.Errors)
        val errors = (sections.last() as SkillPageSection.Errors).errors
        assertEquals(listOf("broken"), errors.map { it.displayName })
    }

    @Test fun noErrorsMeansNoErrorSection() {
        val sections = skillPageSections(catalog(listOf(skill("p1", SkillRootKind.ProjectRivet))))

        assertTrue(sections.none { it is SkillPageSection.Errors })
    }

    @Test fun manualOnlyMarkerFollowsTheFlag() {
        assertEquals("manual only",
            manualOnlyLabel(skill("manual", SkillRootKind.Global, disableModelInvocation = true)))
        assertNull(manualOnlyLabel(skill("auto", SkillRootKind.Global)))
    }

    @Test fun emptyStateNeedsBothListsEmpty() {
        assertTrue(skillsAreEmpty(catalog(emptyList())))
        assertFalse(skillsAreEmpty(catalog(emptyList(), listOf(error()))))
        assertFalse(skillsAreEmpty(catalog(listOf(skill("p", SkillRootKind.Global)))))
        assertFalse(skillsAreEmpty(null))
    }

    @Test fun countSummaryCallsOutBrokenSkills() {
        assertEquals("Loading…", skillCountSummary(null))
        assertEquals("No skills installed", skillCountSummary(catalog(emptyList())))
        assertEquals("2 installed", skillCountSummary(catalog(listOf(
            skill("a", SkillRootKind.Global), skill("b", SkillRootKind.Global)))))
        assertEquals("1 installed · 1 need attention",
            skillCountSummary(catalog(listOf(skill("a", SkillRootKind.Global)), listOf(error()))))
        assertEquals("1 need attention",
            skillCountSummary(catalog(emptyList(), listOf(error()))))
    }
}
