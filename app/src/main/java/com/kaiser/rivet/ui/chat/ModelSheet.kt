package com.kaiser.rivet.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.offeredReasoning
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.filterModels
import com.kaiser.rivet.ui.provider.labelRes

// dsh's composer picker: a small popover above the composer with a two-row root that
// drills into the grouped model list or the reasoning levels.
private enum class PickerPane { Root, Model, Effort }

// One-tap model + reasoning control for the open chat. It drives the provider
// editor's ViewModel slot so config rules (selectListedModel, credential
// resolution, secure header round-trip) stay in exactly one place.
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
    // Only the id the picker opened for seeds the slot; provider-row taps reseed
    // it themselves, so setActive's flow emission cannot trigger a second fetch.
    val openedFor = remember { activeId }
    var query by rememberSaveable { mutableStateOf("") }
    var reasoningTouched by remember { mutableStateOf(false) }
    var pane by remember { mutableStateOf(PickerPane.Root) }

    fun load(id: String) {
        providersViewModel.startEdit(id)
        providersViewModel.fetchModels()
    }

    val reseed: (String) -> Unit = { id ->
        providersViewModel.setActive(id)
        query = ""
        reasoningTouched = false
        load(id)
    }

    // A staged reasoning change belongs to the provider being left, so it is
    // written before the slot is reseeded for another one. The reseed has to wait
    // for save() to finish: it clears the editor slot on completion.
    val selectProvider: (String) -> Unit = { id ->
        if (reasoningTouched && editorConfig.id.isNotEmpty() && editorConfig.model.isNotBlank()) {
            providersViewModel.save { reseed(id) }
        } else {
            reseed(id)
        }
    }

    // save() clears the shared editor slot, so a reasoning change is written when
    // the picker goes away instead of blanking the sections still on screen.
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

    // fetchModels reports a missing credential through `test`, not `fetchError`.
    val loadError = editorState.fetchError
        ?: editorState.test?.takeIf { !it.ok }?.message
    val filteredModels = remember(editorState.models, editorConfig.model, query) {
        filterModels(editorState.models, editorConfig.model, query)
    }
    val reasoningOptions = offeredReasoning(editorConfig)
    val editorReady = editorConfig.id.isNotEmpty()
    val cardShape = RoundedCornerShape(12.dp)

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Back leaves a sub-view first and only dismisses from the root, so the
        // staged save always runs on the way out.
        BackHandler {
            if (pane == PickerPane.Root) close() else pane = PickerPane.Root
        }
        Box(Modifier.fillMaxSize()) {
            // Outside tap dismisses, like the retired sheet's scrim.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } })
            Column(
                Modifier.align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 96.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .shadow(10.dp, cardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, cardShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                    // Taps on the card's own padding must not reach the dismiss layer.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(vertical = 6.dp),
            ) {
                when (pane) {
                    PickerPane.Root -> {
                        PopoverRow(
                            label = stringResource(R.string.provider_model_label),
                            value = displaySafeText(displayModelName(editorConfig.model)),
                            onClick = { pane = PickerPane.Model },
                        )
                        // Same rule as the selector label: one offered level means the
                        // choice cannot change anything, so the row is not shown.
                        if (reasoningOptions.size > 1) {
                            PopoverRow(
                                label = stringResource(R.string.provider_reasoning_label),
                                value = stringResource(editorConfig.reasoning.labelRes()),
                                onClick = { pane = PickerPane.Effort },
                            )
                        }
                    }

                    PickerPane.Model -> {
                        PaneHeader(stringResource(R.string.provider_model_label)) {
                            pane = PickerPane.Root
                        }
                        when {
                            !editorReady -> ManageProvidersRow {
                                commitPending()
                                onManageProviders()
                            }

                            editorState.fetching -> Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }

                            loadError != null -> Column(Modifier.padding(horizontal = 12.dp)) {
                                Text(loadError, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = providersViewModel::fetchModels) {
                                    Text(stringResource(R.string.provider_fetch_models))
                                }
                            }

                            else -> {
                                if (editorState.models.isNotEmpty()) {
                                    OutlinedTextField(value = query, onValueChange = { query = it },
                                        placeholder = { Text(stringResource(R.string.provider_search_models)) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, top = 4.dp))
                                }
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                                    listState.configs.forEach { config ->
                                        if (config.id == editorConfig.id) {
                                            item(key = "group-${config.id}") {
                                                GroupLabel(displaySafeText(config.name))
                                            }
                                            when {
                                                editorState.models.isEmpty() -> {
                                                    if (editorConfig.model.isNotBlank()) {
                                                        item(key = "current-${config.id}") {
                                                            ModelOptionRow(
                                                                title = displaySafeText(displayModelName(editorConfig.model)),
                                                                subtitle = displaySafeText(editorConfig.model),
                                                                selected = true,
                                                                onSelect = {},
                                                            )
                                                        }
                                                    }
                                                    item(key = "fetch-${config.id}") {
                                                        TextButton(onClick = providersViewModel::fetchModels) {
                                                            Text(stringResource(R.string.provider_fetch_models))
                                                        }
                                                    }
                                                }

                                                filteredModels.models.isEmpty() -> item(key = "nomatch-${config.id}") {
                                                    Text(stringResource(R.string.provider_models_no_match),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                                                }

                                                else -> items(filteredModels.models,
                                                    key = { model -> "${config.id}:${model.id}" }) { model ->
                                                    ModelOptionRow(
                                                        title = displaySafeText(model.label),
                                                        subtitle = displaySafeText(model.id),
                                                        selected = editorConfig.model == model.id,
                                                    ) {
                                                        providersViewModel.updateConfig { it.selectListedModel(model) }
                                                        providersViewModel.save {}
                                                        onDismiss()
                                                    }
                                                }
                                            }
                                        } else {
                                            // Other providers' lists are not loaded; naming the
                                            // provider here switches to it and loads its models.
                                            item(key = "switch-${config.id}") {
                                                ProviderJumpRow(
                                                    name = displaySafeText(config.name),
                                                    model = displaySafeText(displayModelName(config.model)),
                                                ) { selectProvider(config.id) }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ManageProvidersRow {
                                    commitPending()
                                    onManageProviders()
                                }
                            }
                        }
                    }

                    PickerPane.Effort -> {
                        PaneHeader(stringResource(R.string.provider_reasoning_label)) {
                            pane = PickerPane.Root
                        }
                        // Applying a level also closes, so the staged change is written.
                        reasoningOptions.forEach { level ->
                            SelectableRow(
                                title = stringResource(level.labelRes()),
                                selected = editorConfig.reasoning == level,
                            ) {
                                reasoningTouched = true
                                providersViewModel.updateConfig { it.copy(reasoning = level) }
                                close()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopoverRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 12.dp))
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

@Composable
private fun PaneHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun ModelOptionRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Text("✓", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ProviderJumpRow(name: String, model: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(onClick = onClick)
        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(model, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

@Composable
private fun SelectableRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ManageProvidersRow(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.manage_providers))
    }
}
