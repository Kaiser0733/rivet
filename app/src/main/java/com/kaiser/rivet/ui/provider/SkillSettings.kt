package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.SkillCatalog
import com.kaiser.rivet.agent.SkillError
import com.kaiser.rivet.agent.SkillRootKind
import com.kaiser.rivet.R
import com.kaiser.rivet.ui.chat.displaySafeText

/** The settings-family labels for each discovery root, in rank order. */
private val SKILL_GROUP_LABELS = listOf(
    SkillRootKind.ProjectRivet to "This project",
    SkillRootKind.ProjectAgents to "Shared (.agents)",
    SkillRootKind.Global to "All projects",
    SkillRootKind.Bundled to "Built in",
)

/** One page section: a source group, or the malformed entries at the end. */
internal sealed interface SkillPageSection {
    data class Skills(val kind: SkillRootKind, val label: String, val skills: List<Skill>) : SkillPageSection
    data class Errors(val errors: List<SkillError>) : SkillPageSection
}

internal fun skillPageSections(catalog: SkillCatalog): List<SkillPageSection> {
    val groups = SKILL_GROUP_LABELS.mapNotNull { (kind, label) ->
        val skills = catalog.skills.filter { it.source?.kind == kind }
        if (skills.isEmpty()) null else SkillPageSection.Skills(kind, label, skills)
    }
    return if (catalog.errors.isEmpty()) groups else groups + SkillPageSection.Errors(catalog.errors)
}

/** Root-row subtitle: the installed count, with broken skills called out. */
internal fun skillCountSummary(catalog: SkillCatalog?): String = when {
    catalog == null -> "Loading…"
    catalog.skills.isEmpty() && catalog.errors.isEmpty() -> "No skills installed"
    catalog.skills.isEmpty() -> "${catalog.errors.size} need attention"
    catalog.errors.isNotEmpty() -> "${catalog.skills.size} installed · ${catalog.errors.size} need attention"
    else -> "${catalog.skills.size} installed"
}

internal fun skillsAreEmpty(catalog: SkillCatalog?): Boolean =
    catalog != null && catalog.skills.isEmpty() && catalog.errors.isEmpty()

/** The quiet marker shown next to a skill the assistant cannot load on its own. */
internal fun manualOnlyLabel(skill: Skill): String? =
    if (skill.disableModelInvocation) "manual only" else null

/**
 * Settings -> Skills: the grouped catalog, or one skill's read-only detail. Installing
 * lives in SkillInstallDialog; this screen only lists and describes what is installed.
 */
@Composable
internal fun SkillsSettings(
    catalog: SkillCatalog?,
    selectedName: String?,
    onSelect: (String) -> Unit,
    onLoadFiles: suspend (Skill) -> List<String>,
    onInstall: () -> Unit,
) {
    val selected = catalog?.skills?.firstOrNull { it.name == selectedName }
    if (selected != null) {
        SkillDetail(selected, onLoadFiles)
    } else {
        Column(Modifier.fillMaxSize()) {
            InstallRow(onInstall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            when {
                catalog == null -> DetailScroll(Modifier.weight(1f)) {
                    Text("Loading skills…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                skillsAreEmpty(catalog) -> DetailScroll(Modifier.weight(1f)) { EmptySkills() }
                else -> SkillsList(skillPageSections(catalog), onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InstallRow(onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onInstall)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("Install from link", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SkillsList(
    sections: List<SkillPageSection>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        sections.forEach { section ->
            when (section) {
                is SkillPageSection.Skills -> {
                    item(key = "group-${section.kind.name}") {
                        SectionHeading(section.label,
                            Modifier.padding(top = 16.dp, bottom = 4.dp))
                    }
                    items(section.skills, key = { "skill:${section.kind.name}:${it.name}" }) { skill ->
                        SkillRow(skill) { onSelect(skill.name) }
                    }
                }
                is SkillPageSection.Errors -> {
                    item(key = "errors") {
                        Text("Needs attention", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(section.errors, key = { "error:${it.path}" }) { error ->
                        ErrorRow(error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, onOpen: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    manualOnlyLabel(skill)?.let { marker ->
                        Text(marker, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(displaySafeText(skill.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun ErrorRow(error: SkillError) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(displaySafeText(error.displayName), style = MaterialTheme.typography.bodyMedium)
        Text(displaySafeText(error.path), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(displaySafeText(error.reason), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun EmptySkills() {
    Text(
        "No skills are installed. A skill is a folder with a SKILL.md file: put it in this " +
            "project's .rivet/skills folder, or in Rivet's own skills folder, and it works " +
            "here and in any other Agent Skills tool.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SkillDetail(skill: Skill, onLoadFiles: suspend (Skill) -> List<String>) {
    var files by remember(skill.name) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(skill.name) { files = onLoadFiles(skill) }
    DetailScroll {
        Text(displaySafeText(skill.description), style = MaterialTheme.typography.bodyLarge)
        Text(displaySafeText(skill.source?.baseDir.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
        SectionHeading("Invocation", Modifier.padding(top = 20.dp, bottom = 4.dp))
        Text(
            if (skill.disableModelInvocation) "Manual only: the assistant cannot load this skill."
            else "The assistant can load this skill when a task matches it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (skill.userInvocable) "You can run it with /${skill.name}."
            else "The assistant is the only way to load it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (skill.allowedTools.isNotEmpty()) {
            SectionHeading("Allowed tools", Modifier.padding(top = 20.dp, bottom = 4.dp))
            Text(skill.allowedTools.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium)
        }
        if (files.isNotEmpty()) {
            SectionHeading("Bundled files", Modifier.padding(top = 20.dp, bottom = 4.dp))
            files.forEach { file ->
                Text(file, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
