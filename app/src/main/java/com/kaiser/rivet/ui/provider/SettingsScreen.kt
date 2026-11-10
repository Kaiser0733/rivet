package com.kaiser.rivet.ui.provider

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.storage.SessionUsage
import com.kaiser.rivet.storage.diagnostics
import com.kaiser.rivet.R
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.SkillCatalog
import com.kaiser.rivet.agent.SpendCaps
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.ui.RivetThemeMode
import com.kaiser.rivet.ui.chat.displaySafeText

private val MAX_WIDTH = 680.dp

// The section that is open at level 2. Remembered by RivetApp, so Android Back can step
// detail -> list -> dismiss instead of closing the sheet in one go.
internal enum class SettingsSection { Providers, Models, Appearance, Agent, Memory, GitHub, Skills, Updates, About }

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
    skillCatalog: SkillCatalog? = null,
    onRefreshSkills: suspend () -> Unit = {},
    onLoadSkillFiles: suspend (Skill) -> List<String> = { emptyList() },
    onInstallSkill: () -> Unit = {},
    memoryLines: List<String> = emptyList(),
    memoryMarkdown: String = "",
    hasProject: Boolean = false,
    onUpdateMemory: (String) -> Unit = {},
    onForgetMemoryLine: (Int) -> Unit = {},
    spendCaps: SpendCaps = SpendCaps(),
    userPricePerMillionUsd: Double? = null,
    onSaveSpend: (SpendCaps, Double?) -> Unit = { _, _ -> },
    onOpenModels: () -> Unit = {},
    onSendFeedback: () -> Unit = {},
) {
    val state by viewModel.listState.collectAsState()
    val gitHubState by viewModel.gitHubState.collectAsState()
    // Level 2 lives here, not in the host: the sheet is only a container.
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    // A skill's detail is one level below its section list, so Back steps skill -> skills
    // -> settings root and only then falls through to the sheet's own dismiss.
    var selectedSkill by rememberSaveable { mutableStateOf<String?>(null) }
    // A refresh can remove the selected skill; only a resolvable one counts as a level.
    val shownSkill = skillCatalog?.skills?.firstOrNull { it.name == selectedSkill }
    BackHandler(enabled = section != null) {
        if (shownSkill != null) selectedSkill = null else section = null
    }
    // The root row's count and the page both read this catalog; refresh off-main when the
    // sheet opens and again when the Skills page itself is opened.
    LaunchedEffect(Unit) { onRefreshSkills() }
    LaunchedEffect(section) {
        if (section == SettingsSection.Skills) onRefreshSkills()
    }
    var deleteTarget by remember { mutableStateOf<ProviderConfig?>(null) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var confirmYolo by rememberSaveable { mutableStateOf(false) }
    var connectGitHubOpen by rememberSaveable { mutableStateOf(false) }

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

    if (connectGitHubOpen) {
        // The token lives only in this dialog's composition state; it is never saveable
        // and is discarded when the dialog closes.
        var token by remember { mutableStateOf("") }
        LaunchedEffect(gitHubState.connected) {
            if (gitHubState.connected) connectGitHubOpen = false
        }
        AlertDialog(
            onDismissRequest = { if (!gitHubState.busy) connectGitHubOpen = false },
            title = { Text("Connect GitHub", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Paste a personal access token. Prefer a fine-grained token limited to the repositories you push to. Rivet stores it encrypted on this device and never puts it in chat, a command, or the command environment.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        singleLine = true,
                        enabled = !gitHubState.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Token") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    gitHubState.error?.let {
                        Text(displaySafeText(it), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.connectGitHub(token) },
                    enabled = token.isNotBlank() && !gitHubState.busy,
                ) { Text(if (gitHubState.busy) "Connecting…" else "Connect") }
            },
            dismissButton = {
                TextButton(onClick = { connectGitHubOpen = false }, enabled = !gitHubState.busy) {
                    Text("Cancel")
                }
            },
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
            SheetTopBar(
                section = section,
                title = if (section == SettingsSection.Skills) shownSkill?.name else null,
                onClose = onClose,
                onBack = {
                    if (shownSkill != null) selectedSkill = null else section = null
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
            when (section) {
                null -> Column {
                    SectionList(
                    providerSummary = when {
                        state.loadError != null -> "Problem loading providers"
                        active == null -> "Not set"
                        else -> "${active.name} · ${active.model}"
                    },
                    themeSummary = themeMode.name,
                    autonomySummary = autonomyDisplayName(autonomyMode),
                    skillSummary = skillCountSummary(skillCatalog),
                    memorySummary = if (hasProject) "${memoryLines.size} durable line${if (memoryLines.size == 1) "" else "s"}"
                        else "Open a project",
                    gitHubSummary = when {
                        !gitHubState.gitAvailable -> "Git not available"
                        gitHubState.connected -> "Connected"
                        else -> "Not connected"
                    },
                    versionSummary = stringResource(R.string.provider_hint_version, versionName),
                    onSelect = {
                        if (it == SettingsSection.Models) onOpenModels() else section = it
                    },
                    )
                    TextButton(onClick = onSendFeedback,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                            .padding(top = 8.dp)) {
                        Text("Send feedback")
                    }
                }
                SettingsSection.Models -> Unit
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
                SettingsSection.Memory -> MemoryDetail(
                    lines = memoryLines,
                    markdown = memoryMarkdown,
                    hasProject = hasProject,
                    onUpdate = onUpdateMemory,
                    onForget = onForgetMemoryLine,
                )
                SettingsSection.Agent -> AgentDetail(
                    autonomyMode = autonomyMode,
                    autonomySaving = autonomySaving,
                    autonomyError = autonomyError,
                    confirmYolo = { confirmYolo = true },
                    onAutonomyChange = onAutonomyChange,
                    spendCaps = spendCaps,
                    userPricePerMillionUsd = userPricePerMillionUsd,
                    onSaveSpend = onSaveSpend,
                )
                SettingsSection.GitHub -> GitHubDetail(
                    connected = gitHubState.connected,
                    gitAvailable = gitHubState.gitAvailable,
                    busy = gitHubState.busy,
                    error = gitHubState.error,
                    onConnect = { connectGitHubOpen = true },
                    onDisconnect = viewModel::disconnectGitHub,
                )
                SettingsSection.Skills -> SkillsSettings(
                    catalog = skillCatalog,
                    selectedName = selectedSkill,
                    onSelect = { selectedSkill = it },
                    onLoadFiles = onLoadSkillFiles,
                    onInstall = onInstallSkill,
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
    title: String? = null,
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
            title ?: section?.title() ?: stringResource(R.string.settings),
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
    SettingsSection.Models -> "Models"
    SettingsSection.Appearance -> "Appearance"
    SettingsSection.Agent -> "Agent"
    SettingsSection.Memory -> "Memory"
    SettingsSection.GitHub -> "GitHub"
    SettingsSection.Skills -> "Skills"
    SettingsSection.Updates -> "Updates"
    SettingsSection.About -> "About"
}

@Composable
private fun SectionList(
    providerSummary: String,
    themeSummary: String,
    autonomySummary: String,
    skillSummary: String,
    memorySummary: String,
    gitHubSummary: String,
    versionSummary: String,
    onSelect: (SettingsSection) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SettingsSection.entries.forEachIndexed { index, section ->
            val (title, summary) = when (section) {
                SettingsSection.Providers -> "Providers" to providerSummary
                SettingsSection.Models -> "Models" to "Download and run a model on this phone"
                SettingsSection.Appearance -> "Appearance" to themeSummary
                SettingsSection.Agent -> "Agent" to autonomySummary
                SettingsSection.Memory -> "Memory" to memorySummary
                SettingsSection.GitHub -> "GitHub" to gitHubSummary
                SettingsSection.Skills -> "Skills" to skillSummary
                SettingsSection.Updates -> "Updates" to "Check for updates"
                SettingsSection.About -> "About" to versionSummary
            }
            val iconRes = when (section) {
                SettingsSection.Providers -> R.drawable.ic_settings // gear = providers config
                SettingsSection.Models -> R.drawable.ic_processes // local inference
                SettingsSection.Appearance -> R.drawable.ic_visibility // sun/moon for theme
                SettingsSection.Agent -> R.drawable.ic_processes // robot/processes for agent
                SettingsSection.Memory -> R.drawable.ic_files // durable project facts
                SettingsSection.GitHub -> R.drawable.ic_files // repository and token settings
                SettingsSection.Skills -> R.drawable.ic_files // skill folders and instructions
                SettingsSection.Updates -> R.drawable.ic_history // refresh/history for updates
                SettingsSection.About -> R.drawable.ic_files // info/documents for about
            }
            
            SectionListItem(
                iconRes = iconRes,
                title = title,
                summary = summary,
                onClick = { onSelect(section) },
                showDivider = index < SettingsSection.entries.size - 1
            )
        }
    }
}

@Composable
private fun SectionListItem(
    iconRes: Int,
    title: String,
    summary: String,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (showDivider) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 48.dp)
        )
    }
}

@Composable
private fun MemoryDetail(
    lines: List<String>,
    markdown: String,
    hasProject: Boolean,
    onUpdate: (String) -> Unit,
    onForget: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(markdown) { mutableStateOf(markdown) }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit project memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("One durable fact per line. No secrets, transcripts, or task progress.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                RivetOutlinedButton(onClick = {
                    onUpdate(draft)
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    DetailScroll {
        Text("Project memory", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Stored in .rivet/memory.md and covered by project checkpoints. AGENTS.md outranks it when they disagree.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (!hasProject) {
            Text("Open a project to view its memory.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RivetOutlinedButton(onClick = { draft = markdown; editing = true }) {
                    Text(if (lines.isEmpty()) "Write memory" else "Edit")
                }
            }
            Spacer(Modifier.height(10.dp))
            if (lines.isEmpty()) {
                Text("No durable facts yet. Rivet adds them with the remember tool when it learns something worth keeping.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lines.forEachIndexed { index, line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(displaySafeText(line), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onForget(index) }) { Text("Forget") }
                }
            }
        }
    }
}

@Composable
private fun GitHubDetail(
    connected: Boolean,
    gitAvailable: Boolean,
    busy: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    DetailScroll {
        Text("GitHub", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !gitAvailable ->
                    "Git is not available in this build, so GitHub pushes cannot run here."
                connected ->
                    "Connected. Pushes to github.com use the token kept in Android Keystore."
                else ->
                    "Not connected. Public clones and local Git work normally. Connect a token to push to GitHub."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (connected) {
            RivetOutlinedButton(onClick = onDisconnect, enabled = !busy) {
                Text(if (busy) "Disconnecting…" else "Disconnect")
            }
        } else if (gitAvailable) {
            RivetOutlinedButton(onClick = onConnect, enabled = !busy) { Text("Connect") }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(displaySafeText(it), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        if (gitAvailable) {
            Text("Use a fine-grained token scoped to the repositories you push to. Rivet keeps it encrypted on this device and never sends it to the model, a command, or the command environment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("This build has no Git binary, so there is nothing to connect until an update ships it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Level 2 chrome: a scrolling column sized to the sheet's remaining height. */
@Composable
internal fun DetailScroll(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
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
        item { SectionHeading("Current provider", modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) }
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
            item { SectionHeading("Other saved providers", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            items(saved, key = { it.id }) { provider ->
                ProviderRow(provider, isActive = false,
                    onSelect = { onSelect(provider) },
                    onEdit = { onEdit(provider.id) },
                    onDelete = { onDelete(provider) })
            }
        }
        item {
            SectionHeading("Add a provider", Modifier.padding(top = 24.dp, bottom = 8.dp))
        }
        items(presets, key = { it.name }) { type ->
            Row(Modifier.fillMaxWidth().clickable { onNewProvider(type) }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(typeDisplayName(type), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge)
                Icon(painterResource(R.drawable.ic_chevron_right), null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
        
        Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 12.dp))
        
        // Three cards per the reference; the retired rose-intensity slider is gone from
        // the UI, its stored value untouched.
        // The reference lays Light and Dark side by side, System full width below.
        Row(Modifier.fillMaxWidth()) {
            ThemeCard(RivetThemeMode.Light, selected = themeMode == RivetThemeMode.Light,
                enabled = !themeSaving, onClick = { onThemeChange(RivetThemeMode.Light) },
                modifier = Modifier.weight(1f).padding(end = 8.dp))
            ThemeCard(RivetThemeMode.Dark, selected = themeMode == RivetThemeMode.Dark,
                enabled = !themeSaving, onClick = { onThemeChange(RivetThemeMode.Dark) },
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        ThemeCard(RivetThemeMode.System, selected = themeMode == RivetThemeMode.System,
            enabled = !themeSaving, onClick = { onThemeChange(RivetThemeMode.System) })
        
        appearanceError?.let { error ->
            Text(displaySafeText(error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
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
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .heightIn(min = 84.dp)
            .clip(shape),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f),
        shadowElevation = if (selected) 2.dp else 0.dp,
        tonalElevation = if (selected) 1.dp else 0.dp,
        onClick = { /* handled by clickable below */ },
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (enabled) onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(themeIcon(mode)),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    mode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
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
    spendCaps: SpendCaps,
    userPricePerMillionUsd: Double?,
    onSaveSpend: (SpendCaps, Double?) -> Unit,
) {
    var perRunUsd by remember(spendCaps) { mutableStateOf(spendCaps.perRunUsd?.toString().orEmpty()) }
    var monthlyUsd by remember(spendCaps) { mutableStateOf(spendCaps.monthlyUsd?.toString().orEmpty()) }
    var perRunTokens by remember(spendCaps) { mutableStateOf(spendCaps.perRunTokens?.toString().orEmpty()) }
    var monthlyTokens by remember(spendCaps) { mutableStateOf(spendCaps.monthlyTokens?.toString().orEmpty()) }
    var price by remember(userPricePerMillionUsd) {
        mutableStateOf(userPricePerMillionUsd?.toString().orEmpty())
    }
    DetailScroll {
        
        listOf(
            AutonomyMode.Ask to "Ask before changes and commands.",
            AutonomyMode.BasicYolo to "Routine changes can run automatically. Rivet still asks for higher-risk actions.",
            AutonomyMode.Yolo to "Rivet can use its allowed tools without approval prompts.",
        ).forEachIndexed { index, (mode, detail) ->
            val isLast = index == 2
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = mode == autonomyMode,
                        enabled = !autonomySaving,
                        role = Role.RadioButton,
                        onClick = {
                            when (mode) {
                                AutonomyMode.Yolo -> if (autonomyMode != mode) confirmYolo()
                                else -> onAutonomyChange(mode)
                            }
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == autonomyMode, onClick = null, enabled = !autonomySaving)
                Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 12.dp)) {
                    Text(autonomyDisplayName(mode), style = MaterialTheme.typography.bodyLarge)
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!isLast) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 68.dp)
                )
            }
        }
        
        autonomyError?.let { Text(displaySafeText(it), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }

        Spacer(Modifier.height(22.dp))
        SectionHeading("Spend guard")
        Text("Caps pause a run; they never stop it. Blank means no cap. Local models skip this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = perRunUsd, onValueChange = { perRunUsd = it },
            label = { Text("Per-run cap (USD)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = monthlyUsd, onValueChange = { monthlyUsd = it },
            label = { Text("Monthly cap (USD)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        OutlinedTextField(value = perRunTokens, onValueChange = { perRunTokens = it },
            label = { Text("Per-run token cap") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        OutlinedTextField(value = monthlyTokens, onValueChange = { monthlyTokens = it },
            label = { Text("Monthly token cap") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        OutlinedTextField(value = price, onValueChange = { price = it },
            label = { Text("Unknown model price / million tokens (USD)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        TextButton(onClick = {
            onSaveSpend(SpendCaps(
                perRunUsd = perRunUsd.toDoubleOrNull()?.takeIf { it > 0 },
                monthlyUsd = monthlyUsd.toDoubleOrNull()?.takeIf { it > 0 },
                perRunTokens = perRunTokens.toLongOrNull()?.takeIf { it > 0 },
                monthlyTokens = monthlyTokens.toLongOrNull()?.takeIf { it > 0 },
            ), price.toDoubleOrNull()?.takeIf { it > 0 })
        }) { Text("Save spend limits") }
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
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))
        
        SectionHeading(stringResource(R.string.support_heading), modifier = Modifier.padding(bottom = 8.dp))
        SupportSection()
        
        UsageRow(onShowUsage = onShowUsage)
    }
}

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(vertical = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
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
        Row(
            Modifier.fillMaxWidth()
                .then(if (isActive) Modifier else Modifier.clickable(onClick = onSelect))
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isActive, onClick = if (isActive) null else onSelect)
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                val providerDetail = if (provider.type == ProviderType.Local) {
                    "${typeDisplayName(provider.type)} · installed model"
                } else {
                    "${typeDisplayName(provider.type)} · ${provider.model}"
                }
                Text(providerDetail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (provider.type == ProviderType.Local) {
                Text("Managed in Models", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IconButton(onClick = onEdit) {
                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.provider_edit_action),
                        tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.provider_delete_action),
                        tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
    ProviderType.Local -> stringResource(R.string.type_local)
}
