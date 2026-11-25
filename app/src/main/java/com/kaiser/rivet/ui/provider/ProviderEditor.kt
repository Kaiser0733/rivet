package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.resolveLimits
import com.kaiser.rivet.provider.detectedContextWindowForModel
import com.kaiser.rivet.provider.manualContextWindowForModel
import com.kaiser.rivet.provider.offeredReasoning
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.provider.withModelId
import com.kaiser.rivet.ui.FoldReveal
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.ui.RivetLayout
import com.kaiser.rivet.ui.chat.displaySafeText
import java.util.Locale

private val MAX_WIDTH = 640.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    BackHandler(enabled = state.signingIn) { viewModel.cancelChatGptSignIn() }
    LaunchedEffect(state.signInCompleted) {
        if (state.signInCompleted) {
            viewModel.consumeChatGptSignInCompleted()
            onDone()
        }
    }
    val filteredModels = remember(state.models, config.model, modelSearch) {
        filterModels(state.models, config.model, modelSearch)
    }

    if (state.showChatGptIntro) {
        val closeIntro = { viewModel.dismissChatGptPlanIntro(); onDone() }
        BackHandler(onBack = closeIntro)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chatgpt_plan_intro_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.chatgpt_plan_intro_body))
            RivetOutlinedButton(onClick = closeIntro) { Text(stringResource(R.string.got_it)) }
        }
        return
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().weight(1f)
            .align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RivetLayout.pageGutter)
            .padding(top = 16.dp, bottom = 32.dp)) {
            ProviderEditorField(
                value = config.name,
                onValueChange = { v -> viewModel.updateConfig { it.copy(name = v) } },
                label = stringResource(R.string.provider_name_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            FoldReveal(expanded = config.type == ProviderType.OpenAiCompatible || advanced) {
                ProviderEditorField(
                    value = config.baseUrl,
                    onValueChange = { v -> viewModel.updateConfig { it.copy(baseUrl = v) } },
                    label = stringResource(R.string.provider_base_url_label),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RivetOutlinedButton(
                    onClick = {
                        viewModel.startChatGptSignIn { url ->
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (_: Exception) { }
                        }
                    },
                    enabled = !state.signingIn && !state.busy && !state.fetching,
                ) {
                    Text(stringResource(R.string.continue_with_chatgpt))
                }
                if (state.signingIn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        TextButton(onClick = viewModel::cancelChatGptSignIn) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
                if (signedIn) {
                    TextButton(onClick = { viewModel.signOutChatGptPlan() }) {
                        Text(stringResource(R.string.sign_out))
                    }
                }
                state.signInError?.let { msg ->
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                ProviderEditorField(
                    value = state.keyInput,
                    onValueChange = viewModel::setKeyInput,
                    label = stringResource(R.string.provider_api_key_label),
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

            Row(Modifier.fillMaxWidth().padding(top = RivetLayout.sectionGap),
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
                // A failed connection test carries the provider's own sentence (editorError ->
                // ProviderError.text()), so it is untrusted text like any other on this screen.
                Text(displaySafeText(test.message),
                    color = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp))
            }
            state.fetchError?.let { err ->
                // Same rule as the banner in Chat: the model-list request can fail with the
                // provider's own words, which must not reorder the line around them.
                Text(displaySafeText(err), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }

            HorizontalDivider(
                Modifier.padding(top = RivetLayout.sectionGap, bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ProviderEditorField(
                value = config.model,
                onValueChange = { v -> viewModel.updateConfig {
                    it.withModelId(v, state.models)
                } },
                label = stringResource(R.string.provider_model_label),
                placeholder = { Text(stringResource(R.string.provider_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            val resolvedLimits = config.resolveLimits()
            val activeWindow = resolvedLimits.input.tokens
            val activeFormatted = String.format(Locale.US, "%,d", activeWindow)
            val windowSource = when (resolvedLimits.input.source) {
                com.kaiser.rivet.provider.LimitSource.Manual -> "Manual limit"
                com.kaiser.rivet.provider.LimitSource.Learned -> "Learned from a provider error"
                com.kaiser.rivet.provider.LimitSource.Provider -> "From provider"
                com.kaiser.rivet.provider.LimitSource.KnownTable -> "Known model"
                com.kaiser.rivet.provider.LimitSource.Local -> "Local model"
                com.kaiser.rivet.provider.LimitSource.UsageFloor -> "Lower bound from a successful request"
                com.kaiser.rivet.provider.LimitSource.Default -> "Assumed — this model was not detected"
            }
            ProviderEditorField(
                value = state.contextWindowInput,
                onValueChange = viewModel::setContextWindowInput,
                label = stringResource(R.string.provider_context_window_label),
                placeholder = {
                    Text(
                        if (resolvedLimits.input.source == com.kaiser.rivet.provider.LimitSource.Default) {
                            stringResource(R.string.provider_context_window_hint)
                        } else {
                            "$activeFormatted (auto-detected)"
                        }
                    )
                },
                supportingText = {
                    Text("$windowSource: $activeFormatted tokens")
                    if (config.type == com.kaiser.rivet.provider.ProviderType.Local &&
                        config.manualContextWindowForModel() == null
                    ) {
                        Text("Device-capped; the GGUF's own training context is the ceiling.")
                    }
                    if (config.modelLimitLearning.containsKey(config.model)) {
                        Text(
                            "Reset learned limit",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.resetLearnedLimits() },
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            val resolvedOutput = resolvedLimits.output
            ProviderEditorField(
                value = state.maxOutputTokensInput,
                onValueChange = viewModel::setMaxOutputTokensInput,
                label = stringResource(R.string.provider_max_output_tokens_label),
                placeholder = {
                    Text(
                        resolvedOutput?.let { String.format(Locale.US, "%,d (auto-detected)", it.tokens) }
                            ?: stringResource(R.string.provider_max_output_tokens_hint)
                    )
                },
                supportingText = if (resolvedOutput == null) null else {
                    val source = when (resolvedOutput.source) {
                        com.kaiser.rivet.provider.LimitSource.Manual -> "Manual limit"
                        com.kaiser.rivet.provider.LimitSource.Learned -> "Learned from a provider error"
                        com.kaiser.rivet.provider.LimitSource.Provider -> "From provider"
                        com.kaiser.rivet.provider.LimitSource.KnownTable -> "Known model"
                        else -> "Local model"
                    }
                    { Text("$source: ${String.format(Locale.US, "%,d", resolvedOutput.tokens)} tokens") }
                },
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
                ProviderEditorField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    label = stringResource(R.string.provider_search_models),
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
            // Reasoning applies to every turn, so it is not hidden behind the
            // advanced toggle; it only appears when the endpoint offers more than
            // one level.
            val reasoningOptions = offeredReasoning(config)
            if (reasoningOptions.size > 1) {
                Text(stringResource(R.string.provider_reasoning_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                // FlowRow: five levels (OpenRouter) do not fit one 360dp row.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    reasoningOptions.forEach { level ->
                        FilterChip(selected = config.reasoning == level,
                            onClick = { viewModel.updateConfig { it.copy(reasoning = level) } },
                            label = { Text(stringResource(level.labelRes())) })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
                Text(if (advanced) "Hide advanced options" else "Advanced options",
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FoldReveal(expanded = advanced) {
                ProviderEditorField(value = state.headersText, onValueChange = viewModel::updateHeadersText,
                    label = stringResource(R.string.provider_headers_label),
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
            Row(Modifier.fillMaxWidth().padding(horizontal = RivetLayout.pageGutter, vertical = 8.dp),
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

}

@Composable
internal fun ProviderEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = singleLine,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainerHigh,
                unfocusedContainerColor = colors.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (supportingText != null) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                CompositionLocalProvider(
                    LocalContentColor provides colors.onSurfaceVariant,
                ) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall, supportingText)
                }
            }
        }
    }
}

private fun displaySafeModelId(value: String): String {
    val visible = if (value.length <= 90) value else "${value.take(87)}…"
    return displaySafeText(visible)
}

internal fun ReasoningLevel.labelRes(): Int = when (this) {
    ReasoningLevel.Default -> R.string.reasoning_default
    ReasoningLevel.Low -> R.string.reasoning_low
    ReasoningLevel.Medium -> R.string.reasoning_medium
    ReasoningLevel.High -> R.string.reasoning_high
    ReasoningLevel.XHigh -> R.string.reasoning_xhigh
    ReasoningLevel.Max -> R.string.reasoning_max
    ReasoningLevel.Ultra -> R.string.reasoning_ultra
}
