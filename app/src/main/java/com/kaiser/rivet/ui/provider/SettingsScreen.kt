package com.kaiser.rivet.ui.provider

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.kaiser.rivet.runtime.TermuxBridge
import com.kaiser.rivet.runtime.TermuxCommand
import com.kaiser.rivet.workspace.AndroidGrantedFolders
import com.kaiser.rivet.workspace.FolderGrant
import com.kaiser.rivet.workspace.grantDisplayPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val MAX_WIDTH = 680.dp

internal enum class SettingsSection {
    Providers, Appearance, Agent, Phone, PhoneAddOn, Subagents, Memory, GitHub, Skills, Updates, About, Usage,
}

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
    val subagentState by viewModel.subagentPoolState.collectAsState()
    val subagentError by viewModel.subagentPoolError.collectAsState()
    val gitHubState by viewModel.gitHubState.collectAsState()
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var selectedSkill by rememberSaveable { mutableStateOf<String?>(null) }
    val shownSkill = skillCatalog?.skills?.firstOrNull { it.name == selectedSkill }
    val back: () -> Unit = {
        when {
            shownSkill != null -> selectedSkill = null
            section == SettingsSection.PhoneAddOn -> section = SettingsSection.Phone
            section == SettingsSection.GitHub -> section = SettingsSection.Providers
            section == SettingsSection.Updates || section == SettingsSection.Usage -> section = SettingsSection.About
            section == SettingsSection.Agent -> section = SettingsSection.Memory
            section != null -> section = null
            else -> onClose()
        }
    }
    BackHandler(enabled = section != null, onBack = back)
    LaunchedEffect(Unit) { onRefreshSkills() }
    LaunchedEffect(section) {
        if (section == SettingsSection.Skills) onRefreshSkills()
    }
    var deleteTarget by remember { mutableStateOf<ProviderConfig?>(null) }
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

    val active = state.configs.firstOrNull { it.id == state.activeId }
    val saved = state.configs.filterNot { it.id == active?.id }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxSize()) {
            SettingsTopBar(
                section = section,
                title = if (section == SettingsSection.Skills) shownSkill?.name else null,
                onBack = back,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (section) {
                null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    SectionList(
                        providerSummary = when {
                            state.loadError != null -> "Problem loading providers"
                            active == null -> "Not set"
                            else -> active.name
                        },
                        themeSummary = themeMode.name,
                        subagentSummary = subagentSummary(subagentState, state.configs),
                        skillSummary = skillCountSummary(skillCatalog),
                        memorySummary = if (hasProject) "${memoryLines.size} saved" else "No project",
                        versionSummary = versionName,
                        onSelect = { section = it },
                        onSendFeedback = onSendFeedback,
                    )
                }
                SettingsSection.Providers -> ProviderDetail(
                    loadError = state.loadError,
                    headerNotice = state.headerNotice,
                    active = active,
                    saved = saved,
                    onSelect = { viewModel.setActive(it.id) },
                    onEdit = { id -> onEditProvider(id) },
                    onDelete = { provider -> deleteTarget = provider },
                    onNewProvider = onNewProvider,
                    setup = { ProviderSetup(viewModel, onOpenModels, showActions = active == null) },
                    onOpenGitHub = { section = SettingsSection.GitHub },
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
                    onOpenAgent = { section = SettingsSection.Agent },
                )
                SettingsSection.Agent -> AgentDetail(
                    autonomyMode = autonomyMode,
                    autonomySaving = autonomySaving,
                    autonomyError = autonomyError,
                    onAutonomyChange = onAutonomyChange,
                    spendCaps = spendCaps,
                    userPricePerMillionUsd = userPricePerMillionUsd,
                    onSaveSpend = onSaveSpend,
                    subagentSummary = subagentSummary(subagentState, state.configs),
                    onOpenSubagents = { section = SettingsSection.Subagents },
                )
                SettingsSection.Phone -> PhoneAccessDetail(onOpenAddOn = { section = SettingsSection.PhoneAddOn })
                SettingsSection.PhoneAddOn -> PhoneAddOnDetail()
                SettingsSection.Subagents -> SubagentsSettings(
                    state = subagentState,
                    configs = state.configs,
                    error = subagentError,
                    onAllowAgentChoice = viewModel::setSubagentAllowAgentChoice,
                    onToggleModel = viewModel::toggleSubagentModel,
                    onFixedModel = viewModel::setFixedSubagentModel,
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
                SettingsSection.Usage -> DetailScroll {
                    Text(conversationUsage?.diagnostics() ?: "No usage has been reported for this conversation.")
                }
                SettingsSection.Updates -> DetailScroll { UpdatesSection(versionName) }
                SettingsSection.About -> AboutDetail(
                    versionName = versionName,
                    onShowUsage = { section = SettingsSection.Usage },
                    onOpenUpdates = { section = SettingsSection.Updates },
                )
            }
        }
    }
}

@Composable
private fun SettingsTopBar(section: SettingsSection?, title: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), "Back")
        }
        Text(title ?: section?.title() ?: stringResource(R.string.settings),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun SettingsSection.title(): String = when (this) {
    SettingsSection.Providers -> "Account & providers"
    SettingsSection.Appearance -> "Appearance"
    SettingsSection.Agent -> "Permissions & spending"
    SettingsSection.Phone -> "Phone access"
    SettingsSection.PhoneAddOn -> "Phone access add-on"
    SettingsSection.Subagents -> "Subagents"
    SettingsSection.Memory -> "Memory & compaction"
    SettingsSection.GitHub -> "GitHub"
    SettingsSection.Skills -> "Skills"
    SettingsSection.Updates -> "Updates"
    SettingsSection.About -> "About"
    SettingsSection.Usage -> "Conversation usage"
}

@Composable
private fun SectionList(
    providerSummary: String,
    themeSummary: String,
    subagentSummary: String,
    skillSummary: String,
    memorySummary: String,
    versionSummary: String,
    onSelect: (SettingsSection) -> Unit,
    onSendFeedback: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup {
            SectionListItem(R.drawable.ic_profile, SettingsSection.Providers.title(), providerSummary,
                { onSelect(SettingsSection.Providers) }, true)
            SectionListItem(R.drawable.ic_files, SettingsSection.Phone.title(), "",
                { onSelect(SettingsSection.Phone) }, false)
        }
        SettingsGroup {
            SectionListItem(R.drawable.ic_files, SettingsSection.Skills.title(), skillSummary,
                { onSelect(SettingsSection.Skills) }, true)
            SectionListItem(R.drawable.ic_history, SettingsSection.Memory.title(), memorySummary,
                { onSelect(SettingsSection.Memory) }, true)
            SectionListItem(R.drawable.ic_processes, SettingsSection.Subagents.title(), subagentSummary,
                { onSelect(SettingsSection.Subagents) }, true)
            SectionListItem(R.drawable.ic_visibility, SettingsSection.Appearance.title(), themeSummary,
                { onSelect(SettingsSection.Appearance) }, false)
        }
        SettingsGroup {
            SectionListItem(R.drawable.ic_edit, "Send feedback", "", onSendFeedback, true)
            SectionListItem(R.drawable.ic_settings, SettingsSection.About.title(), versionSummary,
                { onSelect(SettingsSection.About) }, false)
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .padding(horizontal = 12.dp), content = content)
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
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        if (summary.isNotBlank()) {
            Text(summary, Modifier.widthIn(max = 104.dp).padding(horizontal = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            color = MaterialTheme.colorScheme.outlineVariant,
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
    onOpenAgent: () -> Unit,
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
        SectionListItem(R.drawable.ic_processes, "Permissions & spending", "", onOpenAgent, true)
        Text("Rivet compacts older conversation history to make room in the model's context.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneAccessDetail(onOpenAddOn: () -> Unit) {
    DetailScroll {
        SectionHeading("Share to Rivet")
        Text("On — Rivet appears in the share sheet",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium)

        SectionHeading("Screen capture")
        Text("Rivet asks before each capture. After you allow it, tap Capture now in the notification.",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium)

        GrantedFoldersSettings()
        TermuxSettings()

        SectionHeading("Advanced")
        SectionListItem(
            iconRes = R.drawable.ic_settings,
            title = "Phone access add-on",
            summary = "Not installed",
            onClick = onOpenAddOn,
            showDivider = false,
        )
    }
}

@Composable
private fun PhoneAddOnDetail() {
    DetailScroll {
        Text("Not installed", style = MaterialTheme.typography.labelLarge)
        Text("The separate add-on would let Rivet read notifications and read or tap in other apps with your permission.")
        Text("On Android 13 and later, if Android blocks a setting, open Settings → Apps → the add-on → More → Allow restricted settings. Follow the on-screen instructions and grant access only if you trust the add-on.")
        Text("Google Play Protect may scan, warn about, or block apps installed outside Google Play. Keep Play Protect on and review any warning before proceeding.")
    }
}

@Composable
private fun GrantedFoldersSettings() {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val registry = remember(context) { AndroidGrantedFolders(context) }
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<FolderGrant>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    suspend fun update(error: String, action: suspend () -> Unit = {}) {
        try {
            action()
            folders = registry.list()
            feedback = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = error
        }
    }

    LaunchedEffect(registry) { update("Couldn't read folder access.") }
    DisposableEffect(owner, registry) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { update("Couldn't read folder access.") }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && data != null && uri != null) {
            scope.launch { update("Couldn't keep access to that folder. Choose it again.") { registry.add(uri, data.flags) } }
        }
    }
    SectionHeading("Granted folders")
    if (folders.isEmpty()) Text("No folders added.", style = MaterialTheme.typography.bodyMedium)
    folders.forEach { grant ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(grantDisplayPath(grant.treeUri, grant.label),
                    style = MaterialTheme.typography.bodyMedium)
                Text(when {
                    grant.revoked -> "Revoked — add the folder again to restore access"
                    grant.write -> "Read and write"
                    else -> "Read-only"
                }, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                scope.launch { update("Couldn't remove that folder.") { registry.remove(grant.id) } }
            }) { Text("Remove") }
        }
    }
    TextButton(onClick = {
        try { picker.launch(AndroidGrantedFolders.pickerIntent()) }
        catch (_: Exception) { feedback = "Couldn't open the folder picker." }
    }) { Text("Add folder") }
    Text("Some system folders can't be shared directly — pick or create a subfolder instead.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun TermuxSettings() {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val bridge = remember(context) { TermuxBridge(context) }
    val scope = rememberCoroutineScope()
    var installed by remember(bridge) { mutableStateOf(bridge.installed()) }
    var testing by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner, bridge) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) installed = bridge.installed()
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    SectionHeading("Termux bridge")
    Text(if (installed) "Installed" else "Not installed", style = MaterialTheme.typography.bodyMedium)
    Text(TermuxCommand.SETUP, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    TextButton(enabled = installed && !testing, onClick = {
        testing = true
        output = null
        scope.launch {
            try { output = bridge.test() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { output = "Couldn't start the Termux bridge. Try again while Rivet is open." }
            finally { testing = false }
        }
    }) { Text(if (testing) "Testing…" else "Test bridge") }
    output?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(24.dp))
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
    setup: @Composable () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    val headingColor = MaterialTheme.colorScheme.onSurfaceVariant
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
        item { SectionHeading("Current provider", modifier = Modifier.padding(top = 4.dp, bottom = 4.dp), color = headingColor) }
        if (active == null) {
            item {
                Text("Choose the service you want to connect.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        } else {
            item {
                ProviderRow(active, isActive = true, onSelect = {},
                    onEdit = { onEdit(active.id) }, onDelete = { onDelete(active) })
            }
        }
        item(key = "provider-setup") { setup() }
        item { SectionListItem(R.drawable.ic_files, "GitHub", "", onOpenGitHub, true) }
        if (saved.isNotEmpty()) {
            item { SectionHeading("Other saved providers", Modifier.padding(top = 16.dp, bottom = 4.dp), color = headingColor) }
            items(saved, key = { it.id }) { provider ->
                ProviderRow(provider, isActive = false,
                    onSelect = { onSelect(provider) },
                    onEdit = { onEdit(provider.id) },
                    onDelete = { onDelete(provider) })
            }
        }
        item {
            SectionHeading("Add a provider", Modifier.padding(top = 24.dp, bottom = 8.dp), color = headingColor)
        }
        items(presets, key = { it.name }) { type ->
            Row(Modifier.fillMaxWidth().clickable { onNewProvider(type) }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(typeDisplayName(type), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge)
                Icon(painterResource(R.drawable.ic_chevron_right), null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    onAutonomyChange: (AutonomyMode) -> Unit,
    spendCaps: SpendCaps,
    userPricePerMillionUsd: Double?,
    onSaveSpend: (SpendCaps, Double?) -> Unit,
    subagentSummary: String,
    onOpenSubagents: () -> Unit,
) {
    var perRunUsd by remember(spendCaps) { mutableStateOf(spendCaps.perRunUsd?.toString().orEmpty()) }
    var monthlyUsd by remember(spendCaps) { mutableStateOf(spendCaps.monthlyUsd?.toString().orEmpty()) }
    var perRunTokens by remember(spendCaps) { mutableStateOf(spendCaps.perRunTokens?.toString().orEmpty()) }
    var monthlyTokens by remember(spendCaps) { mutableStateOf(spendCaps.monthlyTokens?.toString().orEmpty()) }
    var price by remember(userPricePerMillionUsd) {
        mutableStateOf(userPricePerMillionUsd?.toString().orEmpty())
    }
    DetailScroll {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpenSubagents),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Subagents · $subagentSummary", Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))

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
                            onAutonomyChange(mode)
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
private fun AboutDetail(versionName: String, onShowUsage: () -> Unit, onOpenUpdates: () -> Unit) {
    DetailScroll {
        
        Text(stringResource(R.string.provider_hint_version, versionName),
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))
        
        SectionHeading(stringResource(R.string.support_heading), modifier = Modifier.padding(bottom = 8.dp))
        SupportSection()
        
        SectionListItem(R.drawable.ic_history, "Updates", "", onOpenUpdates, true)
        UsageRow(onShowUsage = onShowUsage)
    }
}

@Composable
internal fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text,
        modifier = modifier.padding(vertical = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = color,
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
                Text(provider.name, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
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
