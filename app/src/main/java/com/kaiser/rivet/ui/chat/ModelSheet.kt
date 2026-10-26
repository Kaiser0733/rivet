package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.offeredReasoning
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.filterModels
import com.kaiser.rivet.ui.provider.labelRes

// One-tap model + reasoning control for the open chat. It drives the provider
// editor's ViewModel slot so config rules (selectListedModel, credential
// resolution, secure header round-trip) stay in exactly one place.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelSheet(
    providersViewModel: ProvidersViewModel,
    activeId: String?,
    onDismiss: () -> Unit,
    onManageProviders: () -> Unit,
) {
    val listState by providersViewModel.listState.collectAsState()
    val editorState by providersViewModel.editorState.collectAsState()
    val editorConfig = editorState.config
    // Only the id the sheet opened for seeds the slot; provider-row taps reseed
    // it themselves, so setActive's flow emission cannot trigger a second fetch.
    val openedFor = remember { activeId }
    var query by rememberSaveable { mutableStateOf("") }
    var reasoningTouched by remember { mutableStateOf(false) }

    fun load(id: String) {
        providersViewModel.startEdit(id)
        providersViewModel.fetchModels()
    }

    val selectProvider: (String) -> Unit = { id ->
        providersViewModel.setActive(id)
        query = ""
        reasoningTouched = false
        load(id)
    }

    // save() clears the shared editor slot, so a reasoning change is written when
    // the sheet goes away instead of blanking the sections still on screen.
    val commitPending: () -> Unit = {
        if (reasoningTouched && editorConfig.id.isNotEmpty() && editorConfig.model.isNotBlank()) {
            providersViewModel.save {}
        }
    }
    val close: () -> Unit = {
        commitPending()
        onDismiss()
    }

    LaunchedEffect(openedFor) {
        if (openedFor != null) load(openedFor)
    }

    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.model_switch),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = close) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onBackground)
                }
            }

            Text(stringResource(R.string.provider_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            listState.configs.forEach { config ->
                val selected = config.id == listState.activeId
                val onSelect = { selectProvider(config.id) }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .then(if (selected) Modifier else Modifier.clickable(onClick = onSelect)),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected,
                        onClick = if (selected) null else onSelect)
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(displaySafeText(config.name),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(displaySafeText(displayModelName(config.model)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f))
            }

            Text(stringResource(R.string.provider_model_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            // fetchModels reports a missing credential through `test`, not `fetchError`.
            val loadError = editorState.fetchError
                ?: editorState.test?.takeIf { !it.ok }?.message
            when {
                editorState.fetching -> CircularProgressIndicator(
                    Modifier.padding(top = 4.dp).size(24.dp))
                loadError != null -> {
                    Text(loadError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = providersViewModel::fetchModels) {
                        Text(stringResource(R.string.provider_fetch_models))
                    }
                }
                editorState.models.isNotEmpty() -> {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text(stringResource(R.string.provider_search_models)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    val filtered = filterModels(editorState.models, editorConfig.model, query)
                    if (filtered.models.isEmpty()) {
                        Text(stringResource(R.string.provider_models_no_match),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 12.dp))
                    } else filtered.models.forEach { model ->
                        ModelRow(model = model, selected = editorConfig.model == model.id) {
                            providersViewModel.updateConfig { it.selectListedModel(model) }
                            providersViewModel.save {}
                            onDismiss()
                        }
                    }
                }
                else -> {
                    if (editorConfig.model.isNotBlank()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = true, onClick = null)
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(displaySafeText(displayModelName(editorConfig.model)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(displaySafeText(editorConfig.model),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    TextButton(onClick = providersViewModel::fetchModels) {
                        Text(stringResource(R.string.provider_fetch_models))
                    }
                }
            }

            val reasoningOptions = offeredReasoning(editorConfig)
            if (reasoningOptions.size > 1) {
                Text(stringResource(R.string.provider_reasoning_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    reasoningOptions.forEach { level ->
                        FilterChip(selected = editorConfig.reasoning == level,
                            onClick = {
                                reasoningTouched = true
                                providersViewModel.updateConfig { it.copy(reasoning = level) }
                            },
                            label = { Text(stringResource(level.labelRes())) })
                    }
                }
            }

            HorizontalDivider(Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f))
            TextButton(onClick = { commitPending(); onManageProviders() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(stringResource(R.string.manage_providers))
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .then(if (selected) Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.32f),
                RoundedCornerShape(8.dp)) else Modifier)
        .clickable(onClick = onSelect)
        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(displaySafeText(model.label), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(displaySafeText(model.id), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f))
}
