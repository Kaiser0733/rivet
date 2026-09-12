package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.offeredReasoning

private val MAX_WIDTH = 640.dp

@Composable
fun ProviderEditor(
    viewModel: ProvidersViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.editorState.collectAsState()
    val config = state.config

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (state.isNew) R.string.provider_new else R.string.provider_edit),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::closeEditor) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
            }
        }

        OutlinedTextField(
            value = config.name,
            onValueChange = { v -> viewModel.updateConfig { it.copy(name = v) } },
            label = { Text(stringResource(R.string.provider_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = config.baseUrl,
            onValueChange = { v -> viewModel.updateConfig { it.copy(baseUrl = v) } },
            label = { Text(stringResource(R.string.provider_base_url_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = viewModel::setKeyInput,
            label = { Text(stringResource(R.string.provider_api_key_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
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
            OutlinedButton(onClick = viewModel::testConnection, enabled = !state.busy) {
                Text(stringResource(R.string.provider_test))
            }
            OutlinedButton(onClick = viewModel::fetchModels, enabled = !state.fetching) {
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
            onValueChange = { v -> viewModel.updateConfig { it.copy(model = v) } },
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
                TextButton(
                    onClick = { viewModel.updateConfig { it.copy(model = model.id) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Text(model.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            model.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val reasoningOptions = offeredReasoning(config.type, config.model)
        if (reasoningOptions.size > 1) {
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

        OutlinedTextField(
            value = state.headersText,
            onValueChange = viewModel::updateHeadersText,
            label = { Text(stringResource(R.string.provider_headers_label)) },
            placeholder = { Text(stringResource(R.string.provider_headers_hint)) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )

        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { viewModel.save(onSaved = onDone) },
                enabled = config.name.isNotBlank() && config.baseUrl.isNotBlank() && config.model.isNotBlank(),
            ) {
                Text(stringResource(R.string.provider_save))
            }
            TextButton(onClick = viewModel::closeEditor) {
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
