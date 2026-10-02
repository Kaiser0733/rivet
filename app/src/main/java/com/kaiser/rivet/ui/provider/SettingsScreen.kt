package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.kaiser.rivet.storage.SessionUsage
import com.kaiser.rivet.storage.diagnostics
import com.kaiser.rivet.R
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.ui.MAX_ROSE_INTENSITY
import com.kaiser.rivet.ui.MIN_ROSE_INTENSITY
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.ui.RivetThemeMode

private val MAX_WIDTH = 680.dp

@Composable
fun SettingsScreen(
    viewModel: ProvidersViewModel,
    versionName: String,
    onClose: () -> Unit,
    onEditProvider: (String) -> Unit,
    onNewProvider: (ProviderType) -> Unit,
    roseIntensity: Int,
    themeMode: RivetThemeMode,
    themeSaving: Boolean,
    onThemeChange: (RivetThemeMode) -> Unit,
    appearanceError: String?,
    autonomyMode: AutonomyMode,
    autonomySaving: Boolean,
    autonomyError: String?,
    onAutonomyChange: (AutonomyMode) -> Unit,
    onRoseIntensityPreview: (Int) -> Unit,
    onRoseIntensityCommit: (Int) -> Unit,
    conversationUsage: SessionUsage? = null,
) {
    val state by viewModel.listState.collectAsState()
    var deleteTarget by remember { mutableStateOf<ProviderConfig?>(null) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var confirmYolo by rememberSaveable { mutableStateOf(false) }
    var selectedIntensity by rememberSaveable { mutableIntStateOf(roseIntensity) }
    LaunchedEffect(roseIntensity) { selectedIntensity = roseIntensity }
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
    val presets = listOf(
        ProviderType.OpenAiCompatible,
        ProviderType.OpenAi,
        ProviderType.Anthropic,
        ProviderType.Gemini,
        ProviderType.OpenRouter,
    )

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.provider_hint_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f))

        LazyColumn(
            Modifier.widthIn(max = MAX_WIDTH).fillMaxSize().align(Alignment.CenterHorizontally),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        ) {
            state.loadError?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)) }
            }
            item {
                SectionHeading("Updates")
                UpdatesSection(versionName)
            }
            item { SectionHeading("Current provider", Modifier.padding(top = 22.dp)) }
            if (active == null) {
                item {
                    Text("Choose a provider below to connect Rivet to a model.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                item {
                    ProviderRow(active, isActive = true, onSelect = {},
                        onEdit = { onEditProvider(active.id) }, onDelete = { deleteTarget = active })
                }
            }

            if (saved.isNotEmpty()) {
                item { SectionHeading("Other saved providers", Modifier.padding(top = 22.dp)) }
                items(saved, key = { it.id }) { provider ->
                    ProviderRow(provider, isActive = false,
                        onSelect = { viewModel.setActive(provider.id) },
                        onEdit = { onEditProvider(provider.id) },
                        onDelete = { deleteTarget = provider })
                }
            }

            item {
                SectionHeading("Agent autonomy", Modifier.padding(top = 24.dp))
                listOf(
                    AutonomyMode.Ask to "Ask before changes and commands.",
                    AutonomyMode.BasicYolo to "Routine changes can run automatically. Rivet still asks for higher-risk actions.",
                    AutonomyMode.Yolo to "Rivet can use its allowed tools without approval prompts.",
                ).forEach { (mode, detail) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = mode == autonomyMode, enabled = !autonomySaving,
                            role = Role.RadioButton, onClick = {
                                when (mode) {
                                    AutonomyMode.Yolo -> if (autonomyMode != mode) confirmYolo = true
                                    else -> onAutonomyChange(mode)
                                }
                            }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == autonomyMode, onClick = null,
                            enabled = !autonomySaving)
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(when (mode) {
                                AutonomyMode.Ask -> "Ask"
                                AutonomyMode.BasicYolo -> "Basic YOLO"
                                AutonomyMode.Yolo -> "YOLO"
                            }, style = MaterialTheme.typography.bodyLarge)
                            Text(detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                autonomyError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
            }

            item {
                SectionHeading("Appearance", Modifier.padding(top = 24.dp))
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                RivetThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = mode == themeMode, enabled = !themeSaving,
                        role = Role.RadioButton, onClick = { onThemeChange(mode) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == themeMode, onClick = null, enabled = !themeSaving)
                        Text(mode.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (themeMode == RivetThemeMode.Rose) {
                    Text("Rose intensity", style = MaterialTheme.typography.titleSmall)
                    Text("Adjust Rivet's colors. Device brightness stays the same.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = selectedIntensity.toFloat(),
                        onValueChange = { value ->
                            selectedIntensity = value.roundToInt()
                            onRoseIntensityPreview(selectedIntensity)
                        },
                        onValueChangeFinished = { onRoseIntensityCommit(selectedIntensity) },
                        modifier = Modifier.semantics { contentDescription = "Rose intensity" },
                        valueRange = MIN_ROSE_INTENSITY.toFloat()..MAX_ROSE_INTENSITY.toFloat(),
                        steps = 5,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                        Text("Dim", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Text("Original", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { showUsage = true }) { Text("Conversation usage") }
                appearanceError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
    }
}

@Composable
fun typeDisplayName(type: ProviderType): String = when (type) {
    ProviderType.OpenAiCompatible -> stringResource(R.string.type_openai_compatible)
    ProviderType.OpenAi -> stringResource(R.string.type_openai)
    ProviderType.Anthropic -> stringResource(R.string.type_anthropic)
    ProviderType.Gemini -> stringResource(R.string.type_gemini)
    ProviderType.OpenRouter -> stringResource(R.string.type_openrouter)
}
