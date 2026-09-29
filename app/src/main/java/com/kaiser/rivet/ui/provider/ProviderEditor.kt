package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.offeredReasoning
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.ui.RivetOutlinedButton

private val MAX_WIDTH = 640.dp

@Composable
fun ProviderEditor(
    viewModel: ProvidersViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.editorState.collectAsState()
    val config = state.config
    var advanced by remember(config.id) { mutableStateOf(state.headersText.isNotEmpty()) }
    var showKey by remember(config.id) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
      Column(
          Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().fillMaxHeight()
              .align(Alignment.TopCenter)
              .statusBarsPadding()
              .navigationBarsPadding()
              .imePadding()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp),
      ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::closeEditor) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                if (state.isNew) "Add provider" else "Change model",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
        }

        OutlinedTextField(
            value = config.name,
            onValueChange = { v -> viewModel.updateConfig { it.copy(name = v) } },
            label = { Text(stringResource(R.string.provider_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (config.type == ProviderType.OpenAiCompatible || advanced) {
            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = { v -> viewModel.updateConfig { it.copy(baseUrl = v) } },
                label = { Text(stringResource(R.string.provider_base_url_label)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = viewModel::setKeyInput,
            label = { Text(stringResource(R.string.provider_api_key_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = if (state.keyInput.isNotEmpty()) {
                {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(painterResource(if (showKey) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            if (showKey) "Hide API key" else "Show API key",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else null,
            supportingText = {
                if (state.keyPresent && state.keyInput.isEmpty()) {
                    Text(stringResource(R.string.provider_api_key_present))
                }
            },
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RivetOutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.busy && !state.fetching,
            ) {
                Text(stringResource(R.string.provider_test))
            }
            RivetOutlinedButton(
                onClick = viewModel::fetchModels,
                enabled = !state.busy && !state.fetching,
            ) {
                Text(stringResource(R.string.provider_fetch_models))
            }
        }
        if (state.busy || state.fetching) {
            CircularProgressIndicator(
                Modifier.padding(top = 8.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
        }

        state.test?.let { test ->
            Text(
                test.message,
                color = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.fetchError?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        OutlinedTextField(
            value = config.model,
            onValueChange = { v -> viewModel.updateConfig {
                it.copy(model = v, modelContextLimit = null, anthropicModelMetadata = null)
            } },
            label = { Text(stringResource(R.string.provider_model_label)) },
            placeholder = { Text(stringResource(R.string.provider_model_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        if (state.models.isNotEmpty()) {
            Text(
                stringResource(R.string.provider_models_loaded, state.models.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
            state.models.forEach { model ->
                val selected = config.model == model.id
                Row(Modifier.fillMaxWidth()
                    .then(if (selected) Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.32f),
                            RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { viewModel.updateConfig { it.selectListedModel(model) } }
                    .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected,
                        onClick = { viewModel.updateConfig { it.selectListedModel(model) } })
                    Column(Modifier.weight(1f)) {
                        Text(model.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            model.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f))
            }
        }

        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f))
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onBackground)
            Text(if (advanced) "Hide advanced options" else "Advanced options",
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onBackground)
        }
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f))
        val reasoningOptions = offeredReasoning(config)
        if (advanced && reasoningOptions.size > 1) {
            Text(
                stringResource(R.string.provider_reasoning_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                reasoningOptions.forEach { level ->
                    FilterChip(
                        selected = config.reasoning == level,
                        onClick = { viewModel.updateConfig { it.copy(reasoning = level) } },
                        label = { Text(level.name) },
                    )
                }
            }
        }

        if (advanced) {
            OutlinedTextField(
                value = state.headersText,
                onValueChange = viewModel::updateHeadersText,
                label = { Text(stringResource(R.string.provider_headers_label)) },
                placeholder = { Text(stringResource(R.string.provider_headers_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }

        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RivetOutlinedButton(
                onClick = { viewModel.save(onSaved = onDone) },
                enabled = config.name.isNotBlank() && config.baseUrl.isNotBlank() && config.model.isNotBlank(),
            ) {
                Text(stringResource(R.string.provider_save))
            }
            RivetOutlinedButton(onClick = viewModel::closeEditor) {
                Text(stringResource(R.string.provider_cancel))
            }
            if (!state.isNew) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::deleteAndClose) {
                    Text(stringResource(R.string.provider_delete))
                }
            }
        }

        Spacer(Modifier.size(24.dp))
      }
    }
}
