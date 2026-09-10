package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.ProviderType

private val MAX_WIDTH = 640.dp

// AlertDialog is the only experimental Material3 API in this file.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ProvidersViewModel,
    versionName: String,
    onClose: () -> Unit,
    onEditProvider: (String) -> Unit,
    onNewProvider: (ProviderType) -> Unit,
) {
    val state by viewModel.listState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.provider_hint_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = MAX_WIDTH),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.provider_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            if (state.configs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.provider_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.provider_none_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.configs, key = { it.id }) { config ->
                val isActive = config.id == state.activeId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isActive, onClick = { viewModel.setActive(config.id) })
                    Column(Modifier.weight(1f)) {
                        Text(config.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            config.type.name + " · " + config.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onEditProvider(config.id) }) {
                        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.provider_edit_action))
                    }
                    var confirmDelete by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.provider_delete_action))
                    }
                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = { Text(config.name) },
                            text = { Text(stringResource(R.string.provider_delete_confirm)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmDelete = false
                                    viewModel.delete(config.id)
                                }) { Text(stringResource(R.string.provider_delete)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDelete = false }) {
                                    Text(stringResource(R.string.provider_cancel))
                                }
                            },
                        )
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }

            item {
                Text(
                    stringResource(R.string.provider_add),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val presets = listOf(
                ProviderType.OpenAiCompatible,
                ProviderType.OpenAi,
                ProviderType.Anthropic,
                ProviderType.Gemini,
                ProviderType.OpenRouter,
            )
            items(presets, key = { it.name }) { type ->
                TextButton(onClick = { onNewProvider(type) }) {
                    Text(typeDisplayName(type))
                }
            }
        }
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
