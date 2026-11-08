package com.kaiser.rivet.ui.provider

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.storage.SessionUsage
import com.kaiser.rivet.storage.diagnostics
import com.kaiser.rivet.R
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.ui.RivetThemeMode
import com.kaiser.rivet.ui.chat.displaySafeText

private val MAX_WIDTH = 680.dp

// The section that is open at level 2. Remembered by RivetApp, so Android Back can step
// detail -> list -> dismiss instead of closing the sheet in one go.
internal enum class SettingsSection { Providers, Appearance, Agent, Updates, About }

/**
 * The body of the settings modal sheet: a two-level page — a section list, and one
 * section's detail inside the same sheet. Hosting (the sheet itself) is RivetApp's job;
 * this composable only fills it.
 */
@Composable
fun SettingsScreen(
    viewModel: ProvidersViewModel,
    versionName: String,
    onClose: () -> Unit,
    onEditProvider: (String) -> Unit,
    onNewProvider: (ProviderType) -> Unit,
    themeMode: RivetThemeMode,
    themeSaving: Boolean,
    onThemeChange: (RivetThemeMode) -> Unit,
    appearanceError: String?,
    autonomyMode: AutonomyMode,
    autonomySaving: Boolean,
    autonomyError: String?,
    onAutonomyChange: (AutonomyMode) -> Unit,
    conversationUsage: SessionUsage? = null,
) {
    val state by viewModel.listState.collectAsState()
    // Level 2 lives here, not in the host: the sheet is only a container.
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    // Only while a detail is open, so Back inside the sheet steps detail -> list and then
    // falls through to the sheet's own dismiss.
    BackHandler(enabled = section != null) { section = null }
    var deleteTarget by remember { mutableStateOf<ProviderConfig?>(null) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var confirmYolo by rememberSaveable { mutableStateOf(false) }

    deleteTarget?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove ${provider.name}?",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = { Text(stringResource(R.string.provider_delete_confirm),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)) },
            confirmButton = { RivetOutlinedButton(onClick = {
                deleteTarget = null
                viewModel.delete(provider.id)
            }) { Text(stringResource(R.string.provider_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) {
                Text(stringResource(R.string.provider_cancel))
            } },
        )
    }
    if (confirmYolo) {
        AlertDialog(
            onDismissRequest = { confirmYolo = false },
            title = { Text("Enable YOLO?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("Rivet can edit or delete project files, run project commands, download files, and start allowed processes without asking. Commands run with Rivet's app privileges. Project checkpoints, workspace checks, and Rivet's capability limits still apply.",
                style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { RivetOutlinedButton(onClick = {
                confirmYolo = false
                onAutonomyChange(AutonomyMode.Yolo)
            }) { Text("Enable YOLO") } },
            dismissButton = { TextButton(onClick = { confirmYolo = false }) { Text("Cancel") } },
        )
    }

    if (showUsage) {
        AlertDialog(
            onDismissRequest = { showUsage = false },
            title = { Text("Conversation usage") },
            text = { Text(conversationUsage?.diagnostics() ?: "No usage has been reported for this conversation.",
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Default)) },
            confirmButton = { TextButton(onClick = { showUsage = false }) { Text("Close") } },
        )
    }

    val active = state.configs.firstOrNull { it.id == state.activeId }
    val saved = state.configs.filterNot { it.id == active?.id }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = MAX_WIDTH)) {
            SheetTopBar(section = section, onClose = onClose, onBack = { section = null })
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
            when (section) {
                null -> SectionList(
                    providerSummary = when {
                        state.loadError != null -> "Problem loading providers"
                        active == null -> "Not set"
                        else -> "${active.name} · ${active.model}"
                    },
                    themeSummary = themeMode.name,
                    autonomySummary = autonomyDisplayName(autonomyMode),
                    versionSummary = stringResource(R.string.provider_hint_version, versionName),
                    onSelect = { section = it },
                )
                SettingsSection.Providers -> ProviderDetail(
                    loadError = state.loadError,
                    headerNotice = state.headerNotice,
                    active = active,
                    saved = saved,
                    onSelect = { viewModel.setActive(it.id) },
                    onEdit = { id -> onEditProvider(id) },
                    onDelete = { provider -> deleteTarget = provider },
                    onNewProvider = onNewProvider,
                )
                SettingsSection.Appearance -> AppearanceDetail(
                    themeMode = themeMode,
                    themeSaving = themeSaving,
                    onThemeChange = onThemeChange,
                    appearanceError = appearanceError,
                )
                SettingsSection.Agent -> AgentDetail(
                    autonomyMode = autonomyMode,
                    autonomySaving = autonomySaving,
                    autonomyError = autonomyError,
                    confirmYolo = { confirmYolo = true },
                    onAutonomyChange = onAutonomyChange,
                )
                SettingsSection.Updates -> DetailScroll { UpdatesSection(versionName) }
                SettingsSection.About -> AboutDetail(
                    versionName = versionName,
                    onShowUsage = { showUsage = true },
                )
            }
        }
    }
}

// The sheet supplies its own horizontal padding; the column only centres itself in it.

@Composable
private fun SheetTopBar(
    section: SettingsSection?,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (section == null) {
            Spacer(Modifier.size(48.dp))
        } else {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        Text(
            section?.title() ?: stringResource(R.string.settings),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

private fun SettingsSection.title(): String = when (this) {
    SettingsSection.Providers -> "Providers"
    SettingsSection.Appearance -> "Appearance"
    SettingsSection.Agent -> "Agent"
    SettingsSection.Updates -> "Updates"
    SettingsSection.About -> "About"
}

@Composable
private fun SectionList(
    providerSummary: String,
    themeSummary: String,
    autonomySummary: String,
    versionSummary: String,
    onSelect: (SettingsSection) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SectionRow("Providers", providerSummary) { onSelect(SettingsSection.Providers) }
        }
        item {
            SectionRow("Appearance", themeSummary) { onSelect(SettingsSection.Appearance) }
        }
        item {
            SectionRow("Agent", autonomySummary) { onSelect(SettingsSection.Agent) }
        }
        item {
            SectionRow("Updates", "Check for updates") { onSelect(SettingsSection.Updates) }
        }
        item {
            SectionRow("About", versionSummary) { onSelect(SettingsSection.About) }
        }
    }
}

@Composable
private fun SectionRow(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick)
        .padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(summary, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp))
    }
}

/** Level 2 chrome: a scrolling column sized to the sheet's remaining height. */
@Composable
private fun DetailScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun ProviderDetail(
    loadError: String?,
    headerNotice: String?,
    active: ProviderConfig?,
    saved: List<ProviderConfig>,
    onSelect: (ProviderConfig) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (ProviderConfig) -> Unit,
    onNewProvider: (ProviderType) -> Unit,
) {
    val presets = listOf(
        ProviderType.OpenAiCompatible,
        ProviderType.OpenAi,
        ProviderType.Anthropic,
        ProviderType.Gemini,
        ProviderType.OpenRouter,
        ProviderType.ChatGptPlan,
    )
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
    ) {
        loadError?.let { message ->
            item { Text(displaySafeText(message), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)) }
        }
        headerNotice?.let { message ->
            // App copy today (a count of dropped custom headers), escaped for the same
            // reason as the error rows above: it is a stored string this screen prints.
            item { Text(displaySafeText(message), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)) }
        }
        item { SectionHeading("Current provider") }
        if (active == null) {
            item {
                Text("Choose a provider below to connect Rivet to a model.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        } else {
            item {
                ProviderRow(active, isActive = true, onSelect = {},
                    onEdit = { onEdit(active.id) }, onDelete = { onDelete(active) })
            }
        }
        if (saved.isNotEmpty()) {
            item { SectionHeading("Other saved providers", Modifier.padding(top = 22.dp)) }
            items(saved, key = { it.id }) { provider ->
                ProviderRow(provider, isActive = false,
                    onSelect = { onSelect(provider) },
                    onEdit = { onEdit(provider.id) },
                    onDelete = { onDelete(provider) })
            }
        }
        item {
            SectionHeading("Add a provider", Modifier.padding(top = 24.dp, bottom = 4.dp))
        }
        items(presets, key = { it.name }) { type ->
            Row(Modifier.fillMaxWidth().clickable { onNewProvider(type) }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(typeDisplayName(type), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge)
                Icon(painterResource(R.drawable.ic_chevron_right), null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun AppearanceDetail(
    themeMode: RivetThemeMode,
    themeSaving: Boolean,
    onThemeChange: (RivetThemeMode) -> Unit,
    appearanceError: String?,
) {
    DetailScroll {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        // Three cards per the reference; the retired rose-intensity slider is gone from
        // the UI, its stored value untouched.
        // The reference lays Light and Dark side by side, System full width below.
        Row(Modifier.fillMaxWidth()) {
            ThemeCard(RivetThemeMode.Light, selected = themeMode == RivetThemeMode.Light,
                enabled = !themeSaving, onClick = { onThemeChange(RivetThemeMode.Light) },
                modifier = Modifier.weight(1f).padding(end = 4.dp))
            ThemeCard(RivetThemeMode.Dark, selected = themeMode == RivetThemeMode.Dark,
                enabled = !themeSaving, onClick = { onThemeChange(RivetThemeMode.Dark) },
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        ThemeCard(RivetThemeMode.System, selected = themeMode == RivetThemeMode.System,
            enabled = !themeSaving, onClick = { onThemeChange(RivetThemeMode.System) })
        appearanceError?.let { error ->
            Text(displaySafeText(error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThemeCard(
    mode: RivetThemeMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    Box(modifier.heightIn(min = 84.dp)
        .background(
            color = if (selected) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
        )
        .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
        else Modifier)
        .selectable(selected = selected, enabled = enabled, role = Role.Button,
            shape = shape, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(themeIcon(mode)), null, Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(mode.name, style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The app ships its own icon drawables (no material-icons dependency); ic_visibility is
// the sun stand-in and ic_visibility_off the moon, both simple line glyphs.
@DrawableRes
private fun themeIcon(mode: RivetThemeMode): Int = when (mode) {
    RivetThemeMode.Light -> R.drawable.ic_visibility
    RivetThemeMode.Dark -> R.drawable.ic_visibility_off
    RivetThemeMode.System -> R.drawable.ic_settings
}

@Composable
private fun UsageRow(onShowUsage: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onShowUsage),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Conversation usage", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge)
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AgentDetail(
    autonomyMode: AutonomyMode,
    autonomySaving: Boolean,
    autonomyError: String?,
    confirmYolo: () -> Unit,
    onAutonomyChange: (AutonomyMode) -> Unit,
) {
    DetailScroll {
        listOf(
            AutonomyMode.Ask to "Ask before changes and commands.",
            AutonomyMode.BasicYolo to "Routine changes can run automatically. Rivet still asks for higher-risk actions.",
            AutonomyMode.Yolo to "Rivet can use its allowed tools without approval prompts.",
        ).forEach { (mode, detail) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .selectable(selected = mode == autonomyMode, enabled = !autonomySaving,
                    role = Role.RadioButton, onClick = {
                        when (mode) {
                            AutonomyMode.Yolo -> if (autonomyMode != mode) confirmYolo()
                            else -> onAutonomyChange(mode)
                        }
                    }),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == autonomyMode, onClick = null,
                    enabled = !autonomySaving)
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(autonomyDisplayName(mode), style = MaterialTheme.typography.bodyLarge)
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        autonomyError?.let { Text(displaySafeText(it), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
    }
}

private fun autonomyDisplayName(mode: AutonomyMode): String = when (mode) {
    AutonomyMode.Ask -> "Ask"
    AutonomyMode.BasicYolo -> "Basic YOLO"
    AutonomyMode.Yolo -> "YOLO"
}

@Composable
private fun AboutDetail(versionName: String, onShowUsage: () -> Unit) {
    DetailScroll {
        Text(stringResource(R.string.provider_hint_version, versionName),
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        SectionHeading(stringResource(R.string.support_heading))
        SupportSection()
        Spacer(Modifier.height(16.dp))
        UsageRow(onShowUsage)
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()
            .then(if (isActive) Modifier else Modifier.clickable(onClick = onSelect))
            .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isActive, onClick = if (isActive) null else onSelect)
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                Text("${typeDisplayName(provider.type)} · ${provider.model}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.provider_edit_action),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.provider_delete_action),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
    }
}

@Composable
fun typeDisplayName(type: ProviderType): String = when (type) {
    ProviderType.OpenAiCompatible -> stringResource(R.string.type_openai_compatible)
    ProviderType.OpenAi -> stringResource(R.string.type_openai)
    ProviderType.Anthropic -> stringResource(R.string.type_anthropic)
    ProviderType.Gemini -> stringResource(R.string.type_gemini)
    ProviderType.OpenRouter -> stringResource(R.string.type_openrouter)
    ProviderType.ChatGptPlan -> stringResource(R.string.type_chatgpt_plan)
}
