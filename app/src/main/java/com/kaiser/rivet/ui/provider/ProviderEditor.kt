package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.offeredReasoning
import com.kaiser.rivet.provider.CHATGPT_DYNAMIC_CLIENT_ID
import com.kaiser.rivet.provider.ChatGptSignInResult
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.runSignIn
import com.kaiser.rivet.storage.HostIdStore
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.ui.chat.displaySafeText
import kotlinx.coroutines.launch

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
    var modelSearch by remember(config.id, state.models) { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signingIn by remember(config.id) { mutableStateOf(false) }
    var signInError by remember(config.id) { mutableStateOf<String?>(null) }
    var showIntroDialog by remember(config.id) { mutableStateOf(false) }
    val planNotGrantedMsg = stringResource(R.string.chatgpt_plan_not_granted)
    val networkMsg = stringResource(R.string.chatgpt_signin_network)
    val failedMsg = stringResource(R.string.chatgpt_signin_failed)
    val expiredMsg = stringResource(R.string.chatgpt_signin_expired)
    val timeoutMsg = stringResource(R.string.chatgpt_signin_timeout)
    val filteredModels = remember(state.models, config.model, modelSearch) {
        filterModels(state.models, config.model, modelSearch)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::closeEditor) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(if (state.isNew) "Add provider" else "Change model",
                style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f))
        Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().weight(1f)
            .align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)) {
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
                    supportingText = if (config.type == ProviderType.OpenAiCompatible) {
                        { Text(stringResource(R.string.provider_compatible_base_url_hint)) }
                    } else null,
                )
                if (config.type == ProviderType.OpenAiCompatible) {
                    Text(stringResource(R.string.provider_compatible_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                }
            }
            if (config.type == ProviderType.ChatGptPlan) {
                val signedIn = viewModel.chatGptClientId(config.id) != null
                val displayEmail = state.chatGptEmail ?: viewModel.chatGptEmail(config.id)
                if (signedIn) {
                    Text(
                        stringResource(
                            R.string.chatgpt_signed_in_as,
                            displayEmail ?: stringResource(R.string.chatgpt_account_fallback),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                RivetOutlinedButton(
                    onClick = {
                        if (signingIn) return@RivetOutlinedButton
                        scope.launch {
                            signingIn = true
                            signInError = null
                            try {
                                val hostId = HostIdStore(context).getOrCreateHostId()
                                val savedClientId = viewModel.chatGptClientId(config.id)
                                    ?: CHATGPT_DYNAMIC_CLIENT_ID
                                val result = runSignIn(
                                    hostId = hostId,
                                    onAuthorizeUrl = { url ->
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        } catch (_: Exception) { }
                                    },
                                    clientId = savedClientId,
                                )
                                when (result) {
                                    is ChatGptSignInResult.Success -> {
                                        if (state.isNew) {
                                            viewModel.saveChatGptPlanProvider(
                                                result.bundle,
                                                onSaved = {
                                                    if (viewModel.shouldShowChatGptPlanIntro()) {
                                                        viewModel.markChatGptPlanIntroSeen()
                                                        showIntroDialog = true
                                                    } else {
                                                        onDone()
                                                    }
                                                },
                                                onError = { msg -> signInError = msg },
                                            )
                                        } else {
                                            viewModel.updateChatGptPlanBundle(result.bundle)
                                        }
                                    }
                                    ChatGptSignInResult.UserCancelled -> { }
                                    ChatGptSignInResult.PlanNotGranted -> signInError = planNotGrantedMsg
                                    is ChatGptSignInResult.NetworkError -> signInError = networkMsg
                                    ChatGptSignInResult.InvalidCallback -> signInError = failedMsg
                                    ChatGptSignInResult.NeedsReauth -> signInError = expiredMsg
                                    ChatGptSignInResult.TimedOut -> signInError = timeoutMsg
                                    is ChatGptSignInResult.ServerRejected ->
                                        signInError = context.getString(
                                            R.string.chatgpt_signin_server_error, result.code)
                                }
                            } finally {
                                signingIn = false
                            }
                        }
                    },
                    enabled = !signingIn && !state.busy && !state.fetching,
                ) {
                    Text(stringResource(R.string.continue_with_chatgpt))
                }
                if (signingIn) {
                    CircularProgressIndicator(
                        Modifier.padding(top = 8.dp).size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                if (signedIn) {
                    TextButton(onClick = { viewModel.signOutChatGptPlan() }) {
                        Text(stringResource(R.string.sign_out))
                    }
                }
                signInError?.let { msg ->
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
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
                        Column {
                            if (state.keyPresent && state.keyInput.isEmpty())
                                Text(stringResource(R.string.provider_api_key_present))
                            Text(stringResource(R.string.provider_api_key_security_hint))
                        }
                    },
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RivetOutlinedButton(onClick = viewModel::testConnection,
                    enabled = !state.busy && !state.fetching) {
                    Text(stringResource(R.string.provider_test))
                }
                RivetOutlinedButton(onClick = viewModel::fetchModels,
                    enabled = !state.busy && !state.fetching) {
                    Text(stringResource(R.string.provider_fetch_models))
                }
            }
            if (state.busy || state.fetching) {
                CircularProgressIndicator(Modifier.padding(top = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
            state.test?.let { test ->
                Text(test.message,
                    color = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp))
            }
            state.fetchError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
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
                Text(stringResource(R.string.provider_models_loaded, filteredModels.totalCount),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp))
                if (!filteredModels.selectedModelVisible && config.model.isNotBlank()) {
                    Text(stringResource(R.string.provider_model_current, displaySafeModelId(config.model)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                }
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    label = { Text(stringResource(R.string.provider_search_models)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                Text(
                    if (modelSearch.isBlank()) stringResource(R.string.provider_models_showing,
                        filteredModels.models.size, filteredModels.totalCount)
                    else matchingModelCount(filteredModels.matchCount, filteredModels.models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
                if (filteredModels.models.isEmpty()) {
                    Text(stringResource(R.string.provider_models_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                } else filteredModels.models.forEach { model ->
                    val selected = config.model == model.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .then(if (selected) Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.32f),
                                RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { viewModel.updateConfig { it.selectListedModel(model) } }
                        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected,
                            onClick = { viewModel.updateConfig { it.selectListedModel(model) } })
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(displaySafeText(model.label), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(displaySafeText(model.id), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f))
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp),
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
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f))
            val reasoningOptions = offeredReasoning(config)
            if (advanced && reasoningOptions.size > 1) {
                Text(stringResource(R.string.provider_reasoning_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reasoningOptions.forEach { level ->
                        FilterChip(selected = config.reasoning == level,
                            onClick = { viewModel.updateConfig { it.copy(reasoning = level) } },
                            label = { Text(level.name) })
                    }
                }
            }
            if (advanced) {
                OutlinedTextField(value = state.headersText, onValueChange = viewModel::updateHeadersText,
                    label = { Text(stringResource(R.string.provider_headers_label)) },
                    placeholder = { Text(stringResource(R.string.provider_headers_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            }
            if (!state.isNew) {
                TextButton(onClick = viewModel::deleteAndClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.provider_delete))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
        Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().align(Alignment.CenterHorizontally)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RivetOutlinedButton(onClick = { viewModel.save(onSaved = onDone) },
                    modifier = Modifier.weight(1f),
                    enabled = config.name.isNotBlank() && config.baseUrl.isNotBlank() && config.model.isNotBlank()) {
                    Text(stringResource(R.string.provider_save))
                }
                RivetOutlinedButton(onClick = viewModel::closeEditor, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.provider_cancel))
                }
            }
        }
    }
    if (showIntroDialog) {
        AlertDialog(
            onDismissRequest = { showIntroDialog = false; onDone() },
            title = { Text(stringResource(R.string.chatgpt_plan_intro_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            text = { Text(stringResource(R.string.chatgpt_plan_intro_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            confirmButton = {
                RivetOutlinedButton(onClick = { showIntroDialog = false; onDone() }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }
}

private fun displaySafeModelId(value: String): String {
    val visible = if (value.length <= 90) value else "${value.take(87)}…"
    return displaySafeText(visible)
}
