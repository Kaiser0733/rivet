package com.kaiser.rivet.ui.chat

import android.app.Activity
import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.UnsupportedReasoningNotice
import com.kaiser.rivet.chat.commandBusyReason
import com.kaiser.rivet.chat.draftFor
import com.kaiser.rivet.chat.draftToSend
import com.kaiser.rivet.chat.liveStatusLabel
import com.kaiser.rivet.chat.withDraft
import com.kaiser.rivet.chat.liveStatusLine
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.offeredReasoning
import com.kaiser.rivet.ui.provider.labelRes
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentConversationItem
import com.kaiser.rivet.agent.AgentActivityGroup
import com.kaiser.rivet.agent.AgentActivityOperation
import com.kaiser.rivet.agent.ActivityOutcome
import com.kaiser.rivet.agent.AgentActivityProjection
import com.kaiser.rivet.agent.AgentToolLifecycleStage
import com.kaiser.rivet.chat.ChatUiState
import com.kaiser.rivet.chat.ChatErrorAction
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.chat.ProjectBindingState
import com.kaiser.rivet.chat.PROJECT_ACCESS_LOST_MESSAGE
import com.kaiser.rivet.chat.PROJECT_BINDING_MISMATCH_MESSAGE
import com.kaiser.rivet.chat.projectBindingState
import com.kaiser.rivet.ui.RivetDoodle
import com.kaiser.rivet.ui.RivetDoodleMark
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.resolveSignInClientId
import com.kaiser.rivet.provider.runSignIn
import com.kaiser.rivet.provider.ChatGptSignInSession
import com.kaiser.rivet.provider.ChatGptSignInResult
import com.kaiser.rivet.storage.HostIdStore
import com.kaiser.rivet.ui.browser.BrowserWatchCard
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.history.HistoryPane
import com.kaiser.rivet.runtime.ManagedProcessStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(chatViewModel: ChatViewModel, providersViewModel: ProvidersViewModel,
               onOpenSettings: () -> Unit, onOpenHistory: () -> Unit, onOpenProcesses: () -> Unit,
               onStartOffline: () -> Unit = {}, onOpenBrowser: () -> Unit = {},
               onSendFeedback: () -> Unit = {}) {
    val landscape = showsHistoryPane(LocalConfiguration.current.orientation)
    val state by chatViewModel.uiState.collectAsState()
    val browserState by chatViewModel.browser.state.collectAsState()
    val userSkills by chatViewModel.userSkills.collectAsState()
    val providers by providersViewModel.listState.collectAsState()
    val processItems by chatViewModel.managedProcesses.processes.collectAsState()
    val hasActiveProcesses = processItems.any { it.status in setOf(
        ManagedProcessStatus.Starting, ManagedProcessStatus.Running, ManagedProcessStatus.Stopping,
    ) }
    val projectBinding = projectBindingState(state.currentSessionId, state.currentSessionWorkspaceId,
        state.projectIdentity, state.projectLoading)
    val wrongProject = projectBinding == ProjectBindingState.Mismatch
    var pendingProject by remember { mutableStateOf<Pair<Uri, Int>?>(null) }
    var showKeyInput by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    val onIHaveAKey: () -> Unit = { showKeyInput = true }
    var showStartFree by remember { mutableStateOf(false) }
    var modelSheetVisible by remember { mutableStateOf(false) }
    var feedbackMenu by remember { mutableStateOf(false) }
    val planNotGrantedMsg = stringResource(R.string.chatgpt_plan_not_granted)
    val networkMsg = stringResource(R.string.chatgpt_signin_network)
    val failedMsg = stringResource(R.string.chatgpt_signin_failed)
    val expiredMsg = stringResource(R.string.chatgpt_signin_expired)
    val timeoutMsg = stringResource(R.string.chatgpt_signin_timeout)
    val chatScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showChatGptSignInError by remember { mutableStateOf<String?>(null) }
    var showChatGptIntro by remember { mutableStateOf(false) }
    // In-flight sign-in handle: lets a re-tap cancel the previous attempt
    // instead of orphaning its loopback listener.
    var chatGptSession by remember { mutableStateOf<ChatGptSignInSession?>(null) }
    // Cancel sign-in on system back and if the composable is torn down
    // before the coroutine clears the reference.
    BackHandler(enabled = chatGptSession != null) { chatGptSession?.cancel() }
    DisposableEffect(chatGptSession) {
        val session = chatGptSession
        onDispose { session?.cancel() }
    }
    var usageLimitDialogVisible by remember { mutableStateOf(false) }
    var usageLimitShownFor by remember { mutableStateOf<String?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            if (state.currentSessionWorkspaceId != uri.toString() && state.messages.isNotEmpty())
                pendingProject = uri to (result.data?.flags ?: 0)
            else chatViewModel.selectProject(uri, result.data?.flags ?: 0)
        }
    }
    fun chooseProject() {
        if (!state.ready) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        try { picker.launch(intent) }
        catch (_: android.content.ActivityNotFoundException) { chatViewModel.projectPickerUnavailable() }
        catch (_: SecurityException) { chatViewModel.projectPickerUnavailable() }
    }

    pendingProject?.let { selected ->
        AlertDialog(onDismissRequest = { pendingProject = null },
            title = { Text("Start a new conversation?", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = { Text("This conversation is linked to another project. Start a new conversation for the folder you chose?",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)) },
            confirmButton = { RivetOutlinedButton(onClick = {
                pendingProject = null
                chatViewModel.selectProject(selected.first, selected.second)
            }) { Text("Start new") } },
            dismissButton = { TextButton(onClick = { pendingProject = null }) { Text("Cancel") } })
    }
    if (confirmUndo) {
        AlertDialog(onDismissRequest = { confirmUndo = false },
            title = { Text("Undo Rivet's last changes?", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = { Text("Rivet will restore the files it changed. If the project changed afterward, Undo will stop before overwriting newer work.",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)) },
            confirmButton = { RivetOutlinedButton(onClick = {
                confirmUndo = false; chatViewModel.undoLastTurn()
            }) { Text("Undo changes") } },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } })
    }
    if (state.batteryAsk) {
        AlertDialog(
            onDismissRequest = chatViewModel::dismissBatteryAsk,
            title = { Text("Keep Rivet working when the screen is off?") },
            text = { Text("Android may stop long background runs unless Rivet is exempt from battery optimisation. This is asked once; you can change it later in system settings.") },
            confirmButton = { RivetOutlinedButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Exception) { }
                chatViewModel.dismissBatteryAsk()
            }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = chatViewModel::dismissBatteryAsk) { Text("Not now") } },
        )
    }
    confirmDiscard?.let { identity ->
        AlertDialog(onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard Rivet's pending changes?",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = { Text("Rivet will permanently abandon the unsaved runtime copy and reload the current project. Files already in your project will not be changed.",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)) },
            confirmButton = { RivetOutlinedButton(onClick = {
                confirmDiscard = null
                chatViewModel.discardProjectChanges(identity)
            }) { Text("Discard pending changes") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Cancel") } })
    }
    if (showStartFree) {
        val context = LocalContext.current
        AlertDialog(onDismissRequest = { showStartFree = false },
            title = { Text(stringResource(R.string.start_free_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = { Text(stringResource(R.string.start_free_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)) },
            confirmButton = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { }
                    }) { Text(stringResource(R.string.open_openrouter)) }
                    RivetOutlinedButton(onClick = { showStartFree = false; onIHaveAKey() }) {
                        Text(stringResource(R.string.i_have_a_key))
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showStartFree = false }) {
                Text(stringResource(R.string.close))
            } })
    }
    if (showKeyInput) {
        val blankKeyMessage = stringResource(R.string.free_key_blank)
        AlertDialog(onDismissRequest = { showKeyInput = false; keyInput = ""; keyError = null },
            title = { Text(stringResource(R.string.free_key_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; keyError = null },
                        label = { Text(stringResource(R.string.free_key_label)) },
                        placeholder = { Text(stringResource(R.string.free_key_hint)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            autoCorrect = false,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    keyError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                RivetOutlinedButton(onClick = {
                    val trimmed = keyInput.trim()
                    if (trimmed.isBlank()) {
                        keyError = blankKeyMessage
                        return@RivetOutlinedButton
                    }
                    providersViewModel.saveFreeProvider(
                        trimmed,
                        onSaved = {
                            showKeyInput = false
                            keyInput = ""
                            keyError = null
                        },
                        onError = { message -> keyError = message },
                    )
                }) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = { TextButton(onClick = { showKeyInput = false; keyInput = ""; keyError = null }) {
                Text(stringResource(R.string.provider_cancel))
            } })
    }
    state.pendingApproval?.let { approval ->
        ApprovalDialog(approval, chatViewModel::approve, chatViewModel::deny)
    }
    if (showChatGptSignInError != null) {
        AlertDialog(
            onDismissRequest = { showChatGptSignInError = null },
            text = { Text(showChatGptSignInError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { showChatGptSignInError = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    if (showChatGptIntro) {
        AlertDialog(
            onDismissRequest = { showChatGptIntro = false },
            title = { Text(stringResource(R.string.chatgpt_plan_intro_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            text = { Text(stringResource(R.string.chatgpt_plan_intro_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            confirmButton = {
                RivetOutlinedButton(onClick = { showChatGptIntro = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }
    val activeProvider = providers.configs.firstOrNull { it.id == providers.activeId }
    val isChatGptPlanActive = activeProvider?.type == ProviderType.ChatGptPlan
    LaunchedEffect(state.error, activeProvider?.id) {
        val isUsageLimit = state.error == ProviderError.UsageLimit().text()
        if (isUsageLimit && isChatGptPlanActive && usageLimitShownFor != state.error) {
            usageLimitShownFor = state.error
            usageLimitDialogVisible = true
        }
    }
    if (usageLimitDialogVisible) {
        AlertDialog(
            onDismissRequest = { usageLimitDialogVisible = false },
            title = { Text(stringResource(R.string.chatgpt_usage_limit_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            text = { Text(stringResource(R.string.chatgpt_usage_limit_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            confirmButton = {
                RivetOutlinedButton(onClick = {
                    usageLimitDialogVisible = false
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) { }
                }) { Text(stringResource(R.string.manage_usage)) }
            },
            dismissButton = {
                TextButton(onClick = { usageLimitDialogVisible = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (modelSheetVisible) {
        ModelSheet(
            providersViewModel = providersViewModel,
            activeId = providers.activeId,
            onDismiss = { modelSheetVisible = false },
            onManageProviders = {
                modelSheetVisible = false
                onOpenSettings()
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val sidebarWidth = landscapeSidebarWidth(maxWidth.value).dp
        Row(Modifier.fillMaxSize()) {
            if (landscape) {
                HistoryPane(chatViewModel, Modifier.width(sidebarWidth).fillMaxSize())
                VerticalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f))
            }
            // Keep one Chat subtree so the composer and scroll state survive rotation.
            Column(Modifier.weight(1f).fillMaxSize()) {
                // Header row: sessions trigger + title on left, subagent slot + actions on right
                Row(Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (landscape) {
                        Spacer(Modifier.width(96.dp))
                    } else {
                        // Sessions trigger (opens left drawer)
                        IconButton(onClick = onOpenHistory, modifier = Modifier.size(48.dp),
                            enabled = state.ready && !state.projectLoading &&
                                !state.undoing && !state.recoveringProjectChanges) {
                            Icon(painterResource(R.drawable.ic_history), "Conversation history",
                                tint = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                    // Title: session title or project name (tappable to choose project)
                    val titleText = state.currentSessionTitle ?: state.projectName ?: "Rivet"
                    val canChooseProject = providers.configs.isNotEmpty() && state.ready &&
                        !state.streaming && !state.projectLoading && !state.undoing &&
                        !state.recoveringProjectChanges
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .clickable(enabled = canChooseProject, onClick = ::chooseProject),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(titleText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Icon(
                            painterResource(R.drawable.ic_chevron_down),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).padding(start = 2.dp),
                            tint = if (canChooseProject) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f))
                    }
                    // Subagent chip slot (empty for now, filled by subagents feature later)
                    // Reserved space, renders nothing until subagents exist
                    Box(Modifier.size(48.dp)) { }
                    // Browser action
                    IconButton(onClick = onOpenBrowser, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_browser), "Browser",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                    // Processes action
                    IconButton(onClick = onOpenProcesses, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_processes), "Processes",
                            tint = if (hasActiveProcesses) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground)
                    }
                    // Settings action
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                    Box {
                        IconButton(onClick = { feedbackMenu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_more), "More",
                                tint = MaterialTheme.colorScheme.onBackground)
                        }
                        DropdownMenu(expanded = feedbackMenu,
                            onDismissRequest = { feedbackMenu = false }) {
                            DropdownMenuItem(text = { Text("Send feedback") }, onClick = {
                                feedbackMenu = false
                                onSendFeedback()
                            })
                        }
                    }
                    // New session action
                    IconButton(onClick = chatViewModel::newSession, modifier = Modifier.size(48.dp),
                        enabled = state.ready && !state.projectLoading && !state.streaming &&
                            !state.undoing && !state.recoveringProjectChanges) {
                        Icon(painterResource(R.drawable.ic_add),
                            stringResource(R.string.new_conversation),
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
                if (providers.configs.isNotEmpty()) {
                    if (isChatGptPlanActive) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.using_chatgpt_plan),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } catch (_: Exception) { }
                            }) {
                                Text(stringResource(R.string.manage_usage))
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.fillMaxWidth()
                    .align(Alignment.CenterHorizontally).padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.23f))
                if (com.kaiser.rivet.browser.BrowserDrivingPolicy.cardVisible(
                        browserState.agentRunActive, browserState.agentVisualActive,
                        browserState.watchVisible, browserState.visible)) {
                    BrowserWatchCard(browserState, onWatch = { chatViewModel.browser.watch() })
                }
                if (state.projectError != null && projectBinding != ProjectBindingState.AccessLost &&
                    providers.configs.isNotEmpty() && !(state.projectName == null && state.messages.isEmpty())) {
                    Row(Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(state.projectError.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        if (providers.configs.isNotEmpty() &&
                            (state.projectName != null || state.messages.isNotEmpty() || state.projectError != null))
                            TextButton(onClick = ::chooseProject) { Text("Choose again") }
                    }
                }
                if (projectBinding == ProjectBindingState.AccessLost && providers.configs.isNotEmpty() && state.messages.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(PROJECT_ACCESS_LOST_MESSAGE, Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = ::chooseProject) { Text("Choose project again") }
                    }
                }
                if (wrongProject && state.projectName != null && providers.configs.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp)) {
                        Text(PROJECT_BINDING_MISMATCH_MESSAGE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = ::chooseProject) { Text("Choose its project") }
                            TextButton(onClick = chatViewModel::newSession) { Text("Start new conversation") }
                        }
                    }
                }
                if (providers.configs.isEmpty()) {
                    EmptyState(RivetDoodle.Provider, "Choose a model to get started.",
                        "Add a provider, use ChatGPT, or run a model on this phone. Then choose a project.",
                        "Open Settings", onOpenSettings, Modifier.weight(1f),
                        secondaryAction = stringResource(R.string.start_free),
                        onSecondaryAction = { showStartFree = true },
                        tertiaryAction = stringResource(R.string.continue_with_chatgpt),
                        offlineAction = "Start offline",
                        onOfflineAction = onStartOffline,
                        onTertiaryAction = {
                            // Cancel any in-flight attempt first: no orphaned
                            // loopback listeners from repeated taps.
                            chatGptSession?.cancel()
                            chatScope.launch {
                                val session = ChatGptSignInSession()
                                chatGptSession = session
                                try {
                                    showChatGptSignInError = null
                                    val hostIds = HostIdStore(context)
                                    val result = runSignIn(
                                        hostId = hostIds.getOrCreateHostId(),
                                        onAuthorizeUrl = { url ->
                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            } catch (_: Exception) { }
                                        },
                                        clientId = resolveSignInClientId(
                                            bundleClientId = null,
                                            pendingClientId = hostIds.pendingClientId(),
                                        ),
                                        session = session,
                                        hostIds = hostIds,
                                    )
                                    when (result) {
                                        is ChatGptSignInResult.Success -> {
                                            providersViewModel.saveChatGptPlanProvider(
                                                result.bundle,
                                                onSaved = {
                                                    if (providersViewModel.shouldShowChatGptPlanIntro()) {
                                                        providersViewModel.markChatGptPlanIntroSeen()
                                                        showChatGptIntro = true
                                                    }
                                                },
                                                onError = { msg -> showChatGptSignInError = msg },
                                            )
                                        }
                                        ChatGptSignInResult.UserCancelled -> { }
                                        ChatGptSignInResult.PlanNotGranted -> showChatGptSignInError = planNotGrantedMsg
                                        is ChatGptSignInResult.NetworkError -> showChatGptSignInError = networkMsg
                                        ChatGptSignInResult.InvalidCallback -> showChatGptSignInError = failedMsg
                                        ChatGptSignInResult.NeedsReauth -> showChatGptSignInError = expiredMsg
                                        ChatGptSignInResult.TimedOut -> showChatGptSignInError = timeoutMsg
                                        is ChatGptSignInResult.ServerRejected ->
                                            showChatGptSignInError = context.getString(
                                                R.string.chatgpt_signin_server_error, result.code)
                                    }
                                } finally {
                                    if (chatGptSession === session) chatGptSession = null
                                }
                            }
                        })
                } else if (projectBinding == ProjectBindingState.AccessLost && state.messages.isEmpty()) {
                    EmptyState(RivetDoodle.Project, "Rivet no longer has access to this project.",
                        "Choose the folder again to continue this conversation.",
                        "Choose project again", ::chooseProject, Modifier.weight(1f))
                } else if (state.projectName == null && state.projectError != null && state.messages.isEmpty()) {
                    EmptyState(RivetDoodle.Project, "Rivet couldn't open that project.",
                        "Choose the folder again to continue.",
                        "Choose project again", ::chooseProject, Modifier.weight(1f))
                } else if (state.projectName == null && state.messages.isEmpty() && !state.projectLoading) {
                    EmptyState(RivetDoodle.Project, "What do you want to work on?",
                        "Choose the folder that contains your project, then tell Rivet what you want to change.",
                        "Choose project", ::chooseProject, Modifier.weight(1f))
                } else if (state.projectLoading) {
                    EmptyState(RivetDoodle.Project, "Opening your project…",
                        "Rivet is restoring access to the folder you selected.",
                        null, {}, Modifier.weight(1f))
                } else if (state.messages.isEmpty() && !state.streaming && state.error == null && state.notice == null && !state.interruptedTurn && state.goalLine == null) {
                    EmptyState(RivetDoodle.Ready, "Tell Rivet what you want to change.",
                        "Rivet can inspect this project and will ask before changing files.",
                        null, {}, Modifier.weight(1f))
                } else {
                    val visibleState = if (projectBinding == ProjectBindingState.AccessLost &&
                        state.error == PROJECT_ACCESS_LOST_MESSAGE) state.copy(error = null, errorAction = null) else state
                    key(state.currentSessionId) {
                        MessageList(visibleState, chatViewModel::clearError, onOpenSettings, chatViewModel::retryProjectChanges,
                            onDiscardProjectChanges = { confirmDiscard = state.projectIdentity },
                            onRetryInterruptedTurn = chatViewModel::retryInterruptedTurn,
                            onDismissInterruptedTurn = chatViewModel::dismissInterruptedTurn,
                            onAttachGoal = chatViewModel::attachGoalToProject,
                            onRaiseBudget = chatViewModel::raiseBudgetCapForThisRun,
                            onRetryTurn = chatViewModel::retryFailedTurn,
                            onChooseReasoning = chatViewModel::chooseReasoningLevel,
                            modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally))
                    }
                }
                if (state.undoCheckpointId != null || state.undoing) {
                    ChangeSummary(state, onUndo = { confirmUndo = true },
                        onDismissUndo = chatViewModel::dismissUndoBar,
                        modifier = Modifier.fillMaxWidth())
                }
                // Typed explicitly: an untyped lambda would not be inferred as the
                // composable slot the composer takes.
                val modelSelector: (@Composable () -> Unit)? =
                    if (providers.configs.isNotEmpty()) {
                        { ModelSelector(providersViewModel) { modelSheetVisible = true } }
                    } else null
                InputBar(streaming = state.streaming,
                    ready = state.ready && !state.projectLoading && !state.undoing &&
                        !state.recoveringProjectChanges,
                    hasProject = state.projectName != null &&
                        projectBinding == ProjectBindingState.Matched,
                    hasProvider = providers.configs.isNotEmpty(),
                    acceptedMessageCount = state.acceptedMessageCount,
                    error = state.error, notice = state.notice, sessionId = state.currentSessionId,
                    skills = userSkills,
                    onRequestSkills = chatViewModel::refreshUserSkills,
                    selector = modelSelector,
                    onSend = chatViewModel::send, onCancel = chatViewModel::cancel,
                    modifier = Modifier.fillMaxWidth())
                // Status footer: one muted line with turns, tokens, model, and live state
                StatusFooter(state, providers)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EmptyState(kind: RivetDoodle, title: String, detail: String, action: String?,
                       onAction: () -> Unit, modifier: Modifier = Modifier,
                      secondaryAction: String? = null, onSecondaryAction: (() -> Unit)? = null,
                      tertiaryAction: String? = null, onTertiaryAction: (() -> Unit)? = null,
                      offlineAction: String? = null, onOfflineAction: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 390.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RivetDoodleMark(kind)
            // One short title, then the detail as the quieter second voice.
            Text(title, style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium)
            if (action != null || secondaryAction != null || tertiaryAction != null) {
                // Chips, not buttons: a choice offered quietly. FlowRow because three
                // labels do not fit one 360dp row ("Continue with ChatGPT" wrapped
                // mid-word when this was a fixed row).
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (action != null) AssistChip(onClick = onAction, label = { Text(action) })
                    if (secondaryAction != null && onSecondaryAction != null)
                        AssistChip(onClick = onSecondaryAction, label = { Text(secondaryAction) })
                    if (tertiaryAction != null && onTertiaryAction != null)
                        AssistChip(onClick = onTertiaryAction, label = { Text(tertiaryAction) })
                    if (offlineAction != null && onOfflineAction != null)
                        AssistChip(onClick = onOfflineAction, label = { Text(offlineAction) })
                }
            }
        }
    }
}


// The composer's one quiet control: current model, and its effort when the provider
// offers a choice. Both are changed in ModelSheet; this only shows and opens it.
@Composable
private fun ModelSelector(providersViewModel: ProvidersViewModel, onOpen: () -> Unit) {
    val state by providersViewModel.listState.collectAsState()
    val active = state.configs.firstOrNull { it.id == state.activeId }
    val modelLabel = active?.let {
        if (it.type == ProviderType.Local) it.name.removePrefix("Local · ")
        else displayModelName(it.model)
    } ?: stringResource(R.string.model_switch)
    val effortLabel = active?.takeIf { offeredReasoning(it).size > 1 }
        ?.let { stringResource(it.reasoning.labelRes()) }
    TextButton(onClick = onOpen) {
        Text(if (effortLabel != null) "$modelLabel · $effortLabel" else modelLabel,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(painterResource(R.drawable.ic_chevron_down), stringResource(R.string.model_switch),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private data class TimelineDisplayEntry(
    val item: AgentConversationItem,
    val messageIndex: Int? = null,
    val standaloneThinking: String? = null,
)

@Composable
private fun MessageList(state: ChatUiState, onDismissError: () -> Unit,
                        onOpenSettings: () -> Unit, onRetryProjectChanges: () -> Unit,
                        onDiscardProjectChanges: () -> Unit,
                        onRetryInterruptedTurn: () -> Unit,
                        onDismissInterruptedTurn: () -> Unit,
                        onAttachGoal: () -> Unit,
                        onRaiseBudget: () -> Unit,
                        onRetryTurn: () -> Unit,
                        onChooseReasoning: (ReasoningLevel) -> Unit,
                        modifier: Modifier = Modifier) {
    val timeline = remember(state.messages, state.toolLifecycle, state.messageReasoning) {
        val raw = AgentActivityProjection.conversation(state.messages, state.toolLifecycle)
        var cursor = 0
        raw.map { item ->
            when (item) {
                is AgentConversationItem.Message -> {
                    var matched: Int? = null
                    while (cursor < state.messages.size) {
                        val m = state.messages[cursor]
                        cursor++
                        if (m.internalContext != null && m.role != AgentRole.Context) continue
                        if (m.role == AgentRole.User || (m.role == AgentRole.Assistant && m.text.isNotBlank())) {
                            matched = cursor - 1
                            break
                        }
                    }
                    TimelineDisplayEntry(item, matched)
                }
                is AgentConversationItem.Activity -> {
                    var matched: Int? = null
                    for (idx in cursor until state.messages.size) {
                        val m = state.messages[idx]
                        if (m.internalContext != null && m.role != AgentRole.Context) continue
                        if (m.role == AgentRole.Assistant && m.toolCalls.isNotEmpty()) {
                            val groupId = com.kaiser.rivet.agent.AgentToolLifecycle.groupKeyFor(
                                m.toolCalls.asSequence().map { it.id },
                            )
                            if (groupId == item.group.id) {
                                matched = idx
                                if (m.text.isBlank()) cursor = idx + 1
                                break
                            }
                        }
                    }
                    val standalone = matched?.takeIf { state.messages[it].text.isBlank() }
                        ?.let { state.messageReasoning[it] }
                    TimelineDisplayEntry(item, matched, standalone)
                }
            }
        }
    }
    val liveOperation = state.toolLifecycle.lastOrNull { it.stage in setOf(
        AgentToolLifecycleStage.Queued, AgentToolLifecycleStage.AwaitingApproval,
        AgentToolLifecycleStage.Started,
    ) }
    val streamingText = state.streamingText
    val streamingReasoning = state.streamingReasoning
    val streaming = state.streaming
    // While a turn runs the status line is always shown, so a long tool call and a
    // stalled stream never look the same. Outside a turn the plain activity text is
    // still used for recovery messages. The clock's start comes from the turn; without one
    // there is no clock to show, and both the item count and the item itself read this one
    // value so they cannot disagree about whether the row exists.
    val liveStatusStart = if (streaming) state.turnStartedAt else null
    val toolRunning = liveOperation != null
    val writing = streamingText.isNotBlank()
    val waitingForApproval = state.pendingApproval != null || liveOperation?.stage ==
        AgentToolLifecycleStage.AwaitingApproval
    val statusLabel = liveStatusLabel(
        activity = state.activity,
        toolRunning = toolRunning,
        writing = writing,
        waitingForApproval = waitingForApproval,
    )
    val showActivity = !streaming && state.activity != null
    val showInterruptedTurn = state.interruptedTurn && !state.streaming
    val turnItems = remember(timeline) {
        timeline.map { entry ->
            val isUser = entry.item is AgentConversationItem.Message &&
                entry.item.message.role == AgentRole.User
            val isAnswer = entry.item is AgentConversationItem.Message &&
                entry.item.message.role == AgentRole.Assistant
            val isProcess = entry.item is AgentConversationItem.Activity
            TurnItem(
                data = entry,
                isUser = isUser,
                isAnswer = isAnswer,
                isProcess = isProcess,
            )
        }
    }
    val segmentedTurns = remember(turnItems, state.streaming, state.turnDurationsMs, state.interruptedTurn, state.error != null) {
        segmentTurnItems(
            items = turnItems,
            isStreaming = state.streaming,
            turnDurationsMs = state.turnDurationsMs,
            isInterruptedTurn = state.interruptedTurn,
            hasError = state.error != null,
        )
    }
    val totalItems = segmentedTurns.size +
        (if (state.goalLine != null) 1 else 0) +
        (if (streamingReasoning.isNotBlank()) 1 else 0) +
        (if (streamingText.isNotBlank()) 1 else 0) +
        (if (liveStatusStart != null) 1 else 0) +
        (if (showActivity) 1 else 0) +
        (if (showInterruptedTurn) 1 else 0) +
        (if (state.notice != null) 1 else 0) +
        (if (state.error != null) 1 else 0) +
        (if (state.unsupportedReasoning != null) 1 else 0)
    val listState = rememberLazyListState()
    // Saved, not just remembered: a rotation must not yank a reader back to the end,
    // and must not drop a user who was following the reply.
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    val bottomSlop = with(LocalDensity.current) { 72.dp.toPx() }
    val nearBottom by remember(bottomSlop) { derivedStateOf {
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
        if (last.index < layout.totalItemsCount - 1) false
        else layout.viewportEndOffset - (last.offset + last.size) >= -bottomSlop
    } }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { Triple(listState.isScrollInProgress, nearBottom, programmaticScroll) }
            .collect { (scrolling, near, programmatic) ->
                if (scrolling && !programmatic) followBottom = near
            }
    }
    // Sending a message is an explicit request to see it: after reading back through the
    // conversation, the reply and the user's own message must not land off-screen.
    LaunchedEffect(state.acceptedMessageCount) { followBottom = true }
    // Following the bottom while the reader is there. Two things move the list: a row appearing,
    // which this effect lands exactly, and a row growing — and growth is a layout event, not a
    // character count. A wrapped line adds a line height inside the same character bucket, so the
    // character-bucketed trigger this replaced missed it and left the live-status row half below
    // the list's edge until the next bucket, or for good if the stream stalled there (device
    // finding, kaiser, 2026-11-02). Growth is followed by the settle effect below; this one only
    // has to handle rows appearing or disappearing, which move everything at once.
    LaunchedEffect(segmentedTurns.size, streamingReasoning.isNotBlank(), streamingText.isNotBlank(),
        state.streaming, state.activity, state.toolLifecycle, state.interruptedTurn, state.error,
        state.notice) {
        if (followBottom && totalItems > 0) {
            programmaticScroll = true
            try {
                scrollToConversationBottom(listState, totalItems - 1)
            } finally {
                programmaticScroll = false
            }
        }
    }
    // A row that grows is followed from the layout: whenever the content's end — the list's own
    // bottom padding included — sits below the viewport's end, scroll by that gap. This is what
    // keeps the last row fully visible through a wrap, and it settles exactly on the content's
    // end, so the live-status row always keeps the padding under it instead of sitting flush
    // against the list's edge. Not following, not scrolling: a reader who scrolled up owns the
    // position, and the `followBottom` update above is the only thing that changes that.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow 0
            last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
        }.collect { gap ->
            if (!followBottom || gap <= 0 || listState.isScrollInProgress) return@collect
            programmaticScroll = true
            try {
                listState.scrollBy(gap.toFloat())
            } finally {
                programmaticScroll = false
            }
        }
    }
    LazyColumn(state = listState, modifier = modifier.widthIn(max = 748.dp).fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.goalLine?.let { line ->
            item(key = "goal-line") {
                GoalLine(line, state.goalAttachOffer, onAttachGoal)
            }
        }
        // Goal first, then the interruption notice, then the transcript: restoring a
        // saved session must never put the recovery banner above the user's goal.
        if (showInterruptedTurn) item(key = "interrupted-turn") {
            InterruptedTurnRow(
                recovery = state.runRecovery,
                spendLabel = state.spendLabel,
                budgetPaused = state.budgetPaused,
                onRaiseBudget = onRaiseBudget,
                onRetry = onRetryInterruptedTurn,
                onDismiss = onDismissInterruptedTurn,
            )
        }
        items(segmentedTurns, key = { it.turnIndex }) { turn ->
            TurnBlockView(
                turn = turn,
                state = state,
                isLastTurn = turn.turnIndex == segmentedTurns.lastIndex,
            )
        }
        if (streamingReasoning.isNotBlank()) item(key = "streaming-thinking") {
            ReasoningRow(streamingReasoning, streaming = true, id = "streaming")
        }
        if (streamingText.isNotBlank()) item(key = "streaming-assistant") {
            AssistantMessage(displaySafeText(streamingText), streaming = true)
        }
        liveStatusStart?.let { start ->
            item(key = "live-status") { LiveStatusRow(statusLabel, start) }
        }
        if (showActivity) state.activity?.let { activity ->
            item { Text(activity, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        state.notice?.let { notice -> item { Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        state.error?.let { error -> item {
            ErrorRow(error, onDismissError,
                when (state.errorAction) {
                    ChatErrorAction.OpenSettings -> "Open Settings"
                    ChatErrorAction.RetryProjectChanges -> "Try again"
                    ChatErrorAction.RetryTurn -> "Retry"
                    null -> null
                },
                when (state.errorAction) {
                    ChatErrorAction.OpenSettings -> onOpenSettings
                    ChatErrorAction.RetryProjectChanges -> onRetryProjectChanges
                    ChatErrorAction.RetryTurn -> onRetryTurn
                    null -> null
                }, blockedReason = commandBusyReason(state.commandRunning), onDiscard = onDiscardProjectChanges.takeIf {
                    state.errorAction == ChatErrorAction.RetryProjectChanges && !state.streaming &&
                        !state.projectLoading && !state.recoveringProjectChanges && !state.undoing &&
                        state.projectIdentity != null &&
                        state.currentSessionWorkspaceId?.let { it != state.projectIdentity } != true
                })
        } }
        state.unsupportedReasoning?.let { notice -> item(key = "reasoning-choice") {
            ReasoningFallbackRow(notice, onChooseReasoning)
        } }
    }
}

// The model refused an effort. The next step down is offered first, then the rest of
// the ladder; every choice sends the same message again.
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReasoningFallbackRow(notice: UnsupportedReasoningNotice,
                                 onChoose: (ReasoningLevel) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Send the same message again with:", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RivetOutlinedButton(onClick = { onChoose(notice.recommendedLevel) }) {
                Text("Recommended: ${stringResource(notice.recommendedLevel.labelRes())}")
            }
            notice.alternativeLevels.forEach { level ->
                TextButton(onClick = { onChoose(level) }) {
                    Text(stringResource(level.labelRes()))
                }
            }
        }
    }
}

// Ticks once a second from the moment the turn started. The label is recomputed by the
// caller, so a new step ("Writing", a tool name) shows up without restarting the clock.
// The start comes from the turn (ChatUiState.turnStartedAt), not from this row's own state:
// the row is disposed and rebuilt whenever the list relaid out — a long thinking block folding
// does exactly that — and a start held here came back as 0s on device. Keeping it with the turn
// also means a new turn takes a new start instead of inheriting the row's last one.
@Composable
private fun LiveStatusRow(label: String, startedAt: Long) {
    var elapsed by remember(startedAt) {
        mutableStateOf(((SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(liveStatusLine(label, elapsed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TurnProcessRow(
    spec: ChatTurnProcessSpec,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(targetValue = if (open) 180f else 0f, label = "turnChevronRotation")
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(33.dp)
            .drawBehind {
                val strokeWidth = 0.5.dp.toPx()
                val y = size.height - strokeWidth / 2
                drawLine(
                    color = hairlineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = strokeWidth
                )
            }
            .then(
                if (spec.canFold) Modifier.clickable(role = Role.Button, onClick = onToggle)
                else Modifier
            )
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            val label = spec.foldLabel()
            if (spec.durationMs != null && label.startsWith("Completed in ")) {
                Text(
                    text = "Completed in ",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatRunDuration(spec.durationMs),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (spec.canFold) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = if (open) "Collapse turn process" else "Expand turn process",
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun TurnBlockView(
    turn: SegmentedTurn<TimelineDisplayEntry>,
    state: ChatUiState,
    isLastTurn: Boolean,
) {
    var turnOpen by rememberSaveable(turn.turnIndex) { mutableStateOf(!turn.spec.canFold) }
    val isOpen = !turn.spec.canFold || turnOpen

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        turn.userItem?.let { entry ->
            if (entry.item is AgentConversationItem.Message) {
                MessageRow(
                    message = entry.item.message,
                    reasoning = entry.messageIndex?.let { state.messageReasoning[it] },
                    messageKey = entry.messageIndex ?: entry.item.message.hashCode(),
                )
            }
        }

        if (turn.processItems.isNotEmpty()) {
            if (!turn.spec.isLive) {
                TurnProcessRow(
                    spec = turn.spec,
                    open = isOpen,
                    onToggle = { if (turn.spec.canFold) turnOpen = !turnOpen }
                )
            }
            val lastProcess = turn.parts.lastOrNull { it is TurnPart.Process }
            turn.parts.forEach { part ->
                when (part) {
                    is TurnPart.Process -> {
                        if (isOpen) {
                            AnimatedVisibility(
                                visible = true,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                val groups = part.items.mapNotNull { entry ->
                                    (entry.item as? AgentConversationItem.Activity)?.group
                                }
                                if (groups.isNotEmpty()) {
                                    ActivityRow(
                                        groups = groups,
                                        isLive = state.streaming && isLastTurn && part == lastProcess,
                                    )
                                }
                            }
                        }
                    }
                    is TurnPart.Answer -> {
                        if (part.item.item is AgentConversationItem.Message) {
                            MessageRow(
                                message = part.item.item.message,
                                reasoning = part.item.messageIndex?.let { state.messageReasoning[it] },
                                messageKey = part.item.messageIndex ?: part.item.item.message.hashCode(),
                            )
                        }
                    }
                }
            }
        } else {
            turn.parts.forEach { part ->
                if (part is TurnPart.Answer && part.item.item is AgentConversationItem.Message) {
                    MessageRow(
                        message = part.item.item.message,
                        reasoning = part.item.messageIndex?.let { state.messageReasoning[it] },
                        messageKey = part.item.messageIndex ?: part.item.item.message.hashCode(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessGroupHeader(
    title: String,
    activityKind: ProcessActivityKind,
    open: Boolean,
    isLive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(targetValue = if (open) 180f else 0f, label = "chevronRotation")
    val alphaAnim = if (isLive) {
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmerAlpha"
        )
        alpha
    } else 1f

    val iconRes = when (activityKind) {
        ProcessActivityKind.Read -> R.drawable.ic_files
        ProcessActivityKind.ReadImage -> R.drawable.ic_visibility
        ProcessActivityKind.Search -> R.drawable.ic_files
        ProcessActivityKind.Write -> R.drawable.ic_edit
        ProcessActivityKind.Edit -> R.drawable.ic_edit
        ProcessActivityKind.Commands, ProcessActivityKind.Code -> R.drawable.ic_processes
        ProcessActivityKind.WebSearch, ProcessActivityKind.WebFetch -> R.drawable.ic_browser
        ProcessActivityKind.Subagents -> R.drawable.ic_processes
        ProcessActivityKind.Plan -> R.drawable.ic_history
        ProcessActivityKind.Questions -> R.drawable.ic_more
        ProcessActivityKind.Thinking -> R.drawable.ic_history
        ProcessActivityKind.Tools -> R.drawable.ic_processes
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClick = onToggle
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (open) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = if (open) "Collapse" else "Expand",
                    modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation },
                    tint = MaterialTheme.colorScheme.tertiary
                )
            } else {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = activityKind.key,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = alphaAnim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActivityRow(
    groups: List<AgentActivityGroup>,
    isLive: Boolean = false,
) {
    val groupKey = remember(groups) { groups.joinToString("|") { it.id } }
    var expanded by rememberSaveable(groupKey) { mutableStateOf(isLive) }

    val operations = remember(groups) { groups.flatMap { it.operations } }
    val summary = remember(operations, isLive) {
        processGroupSummary(operations, closed = !isLive)
    }
    val counts = summary.counts
    val title = if (isLive) summary.liveLabel() else summary.title()
    val primaryKind = counts.firstOrNull()?.kind ?: ProcessActivityKind.Thinking

    val config = LocalConfiguration.current
    val maxBodyHeight = minOf(400.dp, (config.screenHeightDp * 0.5f).dp)

    Column(Modifier.fillMaxWidth()) {
        ProcessGroupHeader(
            title = title,
            activityKind = primaryKind,
            open = expanded,
            isLive = isLive,
            onToggle = { expanded = !expanded }
        )
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                operations.forEach { operation -> ActivityOperationRow(operation) }
            }
        }
    }
}

@Composable
private fun ActivityOperationRow(operation: AgentActivityOperation) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(operation.title, Modifier.widthIn(min = 64.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
        Column(Modifier.weight(1f)) {
            if (operation.detail.isNotBlank()) Text(displaySafeText(operation.detail),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            operation.outcomeDetail?.let { Text(displaySafeText(it),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(activityOutcomeText(operation.outcome), Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall, color = when (operation.outcome) {
                ActivityOutcome.Failed, ActivityOutcome.Denied, ActivityOutcome.Blocked -> MaterialTheme.colorScheme.error
                ActivityOutcome.Running, ActivityOutcome.WaitingApproval -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
    }
}

private fun activityOutcomeText(outcome: ActivityOutcome): String = when (outcome) {
    ActivityOutcome.Queued -> "Queued"
    ActivityOutcome.WaitingApproval -> "Waiting for approval"
    ActivityOutcome.Running -> "Running"
    ActivityOutcome.Completed -> "Completed"
    ActivityOutcome.Failed -> "Failed"
    ActivityOutcome.Denied -> "Denied"
    ActivityOutcome.Blocked -> "Stopped"
    ActivityOutcome.Cancelled -> "Cancelled"
    ActivityOutcome.Unknown -> "Outcome unknown"
}

private suspend fun scrollToConversationBottom(state: androidx.compose.foundation.lazy.LazyListState, index: Int) {
    state.scrollToItem(index)
    val layout = state.layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    // Bottom-align the item to the content's end, not to the viewport's: the list's bottom
    // padding is part of the content, and leaving it visible is what gives the last row room
    // under it before growth pushes it out of the viewport.
    val viewport = layout.viewportEndOffset - layout.viewportStartOffset
    val offset = item.size + layout.afterContentPadding - viewport
    if (offset != 0) state.scrollToItem(index, offset)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(message: AgentMessage, reasoning: String? = null, messageKey: Int = 0) {
    val context = LocalContext.current
    val copyText = if (message.role == AgentRole.User) message.text else displaySafeText(message.text)
    // Long-press opens a visible menu. Copying used to happen with nothing on screen,
    // so nothing showed that it had worked.
    var menuOpen by remember(messageKey) { mutableStateOf(false) }
    var selectingText by remember(messageKey) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.role == AgentRole.Assistant && !reasoning.isNullOrBlank()) {
            ReasoningRow(reasoning, streaming = false, id = "thinking-$messageKey")
        }
        val bubble: @Composable () -> Unit = {
            if (message.role == AgentRole.User) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        modifier = Modifier.widthIn(max = 525.dp).fillMaxWidth(0.82f),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            message.text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                AssistantMessage(displaySafeText(message.text), streaming = false)
            }
        }
        Box(Modifier.fillMaxWidth().then(
            if (selectingText) Modifier else Modifier.combinedClickable(
                onClick = {},
                onLongClickLabel = "Copy or select text",
                onLongClick = { menuOpen = true },
            ),
        )) {
            if (selectingText) SelectionContainer { bubble() } else bubble()
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Copy message") }, onClick = {
                    menuOpen = false
                    copyToClipboard(context, copyText)
                    Toast.makeText(context, "Message copied.", Toast.LENGTH_SHORT).show()
                })
                DropdownMenuItem(text = { Text("Select text") }, onClick = {
                    menuOpen = false
                    selectingText = true
                })
            }
        }
        if (selectingText) {
            TextButton(onClick = { selectingText = false }) { Text("Done selecting") }
        }
    }
}

// Assistant reasoning row copied from dsh ReasoningRow: collapsed is exactly one 24dp line
// ("Think" + 2dp dot + 13sp preview without asterisks, fade shimmer when streaming).
// Expanded body is indented 22dp with secondary typography, no Surface and no Show/Hide buttons.
@Composable
private fun ReasoningRow(
    text: String,
    streaming: Boolean,
    id: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "reasoningChevronRotation")
    val alphaAnim = if (streaming) {
        val infiniteTransition = rememberInfiniteTransition(label = "reasoningShimmer")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "reasoningAlpha"
        )
        alpha
    } else 1f

    val scan = remember { PreviewScan() }
    val summaryText = remember(text, streaming) {
        val raw = displaySafeText(thinkingPreview(text, streaming, scan))
        raw.replace("**", "").trim()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clickable(
                    role = Role.Button,
                    onClick = { expanded = !expanded }
                )
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (expanded) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = "Collapse",
                        modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation },
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_history),
                        contentDescription = "Think",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Think",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.tertiary
            )
            if (summaryText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alphaAnim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Collapse thinking"
                    ) { expanded = false }
            ) {
                Text(
                    text = displaySafeText(text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BrowserImage(image: ChatMarkdownBlock.Image) {
    val context = LocalContext.current
    val bitmap = remember(image.source) {
        val file = java.io.File(image.source.removePrefix("file://"))
        val allowed = file.canonicalPath.startsWith(context.filesDir.canonicalPath + "/") ||
            file.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/")
        if (allowed) BitmapFactory.decodeFile(file.absolutePath) else null
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = image.alt.ifBlank { "Browser screenshot" },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Text("Browser screenshot unavailable.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AssistantMessage(text: String, streaming: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (streaming) {
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default))
        } else AssistantMarkdown(text)
    }
}

@Composable
private fun AssistantMarkdown(text: String) {
    // The answer grows a few characters at a time while it streams. A full parse per publication
    // re-splits and re-joins the whole reply; this keeps the blocks before the last one and
    // re-parses only that block, with the same result (see MarkdownBlocks).
    val parser = remember { MarkdownBlocks() }
    val blocks = parser.forText(text)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ChatMarkdownBlock.Paragraph -> InlineMarkdown(block.text)
                is ChatMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    InlineMarkdown(block.text, style = style.copy(fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold))
                }
                is ChatMarkdownBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp),
                ) { InlineMarkdown(block.text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) }
                is ChatMarkdownBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(if (block.ordered) "${block.start + index}." else "•",
                                modifier = Modifier.widthIn(min = 24.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default))
                            InlineMarkdown(item, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is ChatMarkdownBlock.Image -> BrowserImage(block)
                is ChatMarkdownBlock.Code -> Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (block.language.isNotBlank()) Text(displaySafeText(block.language),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(start = 2.dp))
                        else Spacer(Modifier.weight(1f))
                        CopyTextButton(block.text, "Copy code")
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                    ) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 9.dp)) {
                            Text(displaySafeText(block.text), softWrap = false,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineMarkdown(text: String, modifier: Modifier = Modifier, style: TextStyle? = null) {
    val textStyle = style ?: MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)
    val runs = remember(text) { parseInlineMarkdown(text) }
    val color = MaterialTheme.colorScheme.onBackground
    val codeBackground = MaterialTheme.colorScheme.surfaceContainer
    val annotated = remember(runs, color, codeBackground, textStyle) {
        buildAnnotatedString {
            runs.forEach { run ->
                withStyle(SpanStyle(
                    color = color,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    fontWeight = if (run.bold) FontWeight.SemiBold else textStyle.fontWeight,
                    fontStyle = if (run.italic) FontStyle.Italic else textStyle.fontStyle,
                    background = if (run.code) codeBackground else Color.Transparent,
                )) { append(displaySafeText(run.text)) }
            }
        }
    }
    Text(annotated, modifier = modifier, style = textStyle.copy(color = color))
}

internal fun approvalTitle(request: AgentApprovalRequest): String = when {
    request.call.name == "run_command" -> "Run a project command?"
    request.dangerous -> request.title
    else -> "Allow Rivet to ${request.title.lowercase()}?"
}

@Composable
private fun ApprovalDialog(request: AgentApprovalRequest, onApprove: (Long) -> Unit,
                           onDeny: (Long) -> Unit) {
    val command = request.call.name == "run_command"
    val delete = request.call.name == "delete_path"
    AlertDialog(onDismissRequest = { onDeny(request.approvalToken) },
        title = { Text(displaySafeText(approvalTitle(request)),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(displaySafeText(request.detail),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily =
                    if (command) FontFamily.Monospace else FontFamily.Default))
            if (command) Text(COMMAND_APPROVAL_WARNING,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Default),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { RivetOutlinedButton(onClick = { onApprove(request.approvalToken) }) {
            Text(if (delete) "Delete" else "Allow")
        } },
        dismissButton = { TextButton(onClick = { onDeny(request.approvalToken) }) {
            Text(if (delete) "Cancel" else "Don't allow")
        } })
}

@Composable
private fun ChangeSummary(state: ChatUiState, onUndo: () -> Unit,
                          onDismissUndo: () -> Unit, modifier: Modifier = Modifier) {
    var details by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { details = !details }, enabled = state.lastTurnFiles.isNotEmpty()) {
                Text(if (state.lastTurnFileCount == 0) "Project changed"
                    else "${state.lastTurnFileCount} file${if (state.lastTurnFileCount == 1) "" else "s"} changed")
            }
            TextButton(onClick = onUndo, enabled = !state.streaming && !state.undoing &&
                state.undoCheckpointId != null) { Text(if (state.undoing) "Undoing…" else "Undo") }
            TextButton(onClick = onDismissUndo,
                enabled = state.undoCheckpointId != null) { Text("×") }
        }
        commandBusyReason(state.commandRunning)?.let { reason ->
            Text(reason, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.spendLabel?.let { spend ->
            Text(if (spend == "unknown pricing") spend else "Spend $spend",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (details) state.lastTurnFiles.forEach { path ->
            Text(displaySafeText(path), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (details && state.lastTurnFileCount > state.lastTurnFiles.size)
            Text("Showing the first ${state.lastTurnFiles.size} files", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InterruptedTurnRow(recovery: String?, spendLabel: String?, budgetPaused: Boolean,
                              onRaiseBudget: () -> Unit,
                              onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ChatViewModel.INTERRUPTED_TURN_PROMPT,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                recovery?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                spendLabel?.let {
                    Text(if (it == "unknown pricing") it else "Spend $it",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                if (budgetPaused) TextButton(onClick = onRaiseBudget) { Text("Raise cap") }
                TextButton(onClick = onRetry) { Text(if (recovery == null) "Retry" else "Resume") }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_error_dismiss)) }
            }
        }
    }
}

@Composable
private fun GoalLine(line: String, attachOffer: Boolean, onAttach: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(displaySafeText(line), modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            if (attachOffer) TextButton(onClick = onAttach) { Text("Attach to project") }
        }
    }
}

// Copies through the system clipboard. The label is also the accessibility name, so a
// screen reader says what the button does rather than repeating the code.
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Rivet", text))
}

@Composable
private fun CopyTextButton(text: String, label: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }
    TextButton(onClick = { copyToClipboard(context, text); copied = true }) {
        Text(if (copied) "Copied" else label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ErrorRow(error: String, onDismiss: () -> Unit, actionLabel: String?, onAction: (() -> Unit)?,
                     onDiscard: (() -> Unit)? = null, blockedReason: String? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Since F1 the banner can carry the provider's own words, which are untrusted text
            // like any other: a bidi control inside them reorders the line around Rivet's own
            // sentence. Every other foreign string on screen goes through displaySafeText.
            Text(displaySafeText(error), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium)
            if (onDiscard != null) TextButton(onClick = onDiscard) { Text("Discard pending changes") }
            if (blockedReason != null) Text(blockedReason, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Row {
                if (onAction != null && actionLabel != null)
                    TextButton(onClick = onAction, enabled = blockedReason == null) { Text(actionLabel) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_error_dismiss)) }
            }
        }
    }
}

// Drafts are a small String map, so a map saver keeps them across process death without
// a bespoke encoding: every key and value is a String, which is what a Bundle can hold.
private val DRAFT_SAVER: Saver<Map<String, String>, Any> = mapSaver(
    save = { drafts -> drafts },
    restore = { saved -> saved.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap() },
)

internal fun isGoalCommand(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed == "/goal" || trimmed.startsWith("/goal ")
}

@Composable
private fun InputBar(streaming: Boolean, ready: Boolean, acceptedMessageCount: Long,
                     hasProject: Boolean = true, hasProvider: Boolean = true,
                     error: String?, notice: String?, sessionId: String?,
                     skills: List<Skill> = emptyList(),
                     onRequestSkills: suspend () -> Unit = {},
                     selector: (@Composable () -> Unit)? = null,
                     onSend: (String) -> Unit,
                     onCancel: () -> Unit, modifier: Modifier = Modifier) {
    // A draft belongs to the conversation it was typed in: switching away keeps it there and
    // never leaves text in the composer that would be sent to a different conversation. The
    // drafts live in one saveable map rather than in a rememberSaveable keyed by the session:
    // a keyed state reverts to its init value when the key changes, which would drop the text
    // on the way out and show an empty composer on the way back.
    var drafts by rememberSaveable(stateSaver = DRAFT_SAVER) {
        mutableStateOf(emptyMap<String, String>())
    }
    val draft = draftFor(drafts, sessionId)
    var pendingText by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedAt by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(acceptedMessageCount, streaming, error, notice, sessionId) {
        if (pendingText != null && acceptedMessageCount > submittedAt) {
            if (draft == pendingText) drafts = withDraft(drafts, sessionId, "")
            pendingText = null
        } else if (!streaming && (error != null || notice != null)) {
            pendingText = null
        }
    }
    // While a reply runs the view model refuses a send, so the composer must not offer
    // one: the keyboard's Send action goes through `submit`, and a refused send used to
    // leave `pendingText` latched with no accepted message to clear it.
    // /goal is a session command and must work before a project (or even a model)
    // exists. Plain text still needs a project and a provider; without a provider the
    // send is allowed so the view model can explain what to choose instead of the
    // button silently refusing the tap.
    val goalCommand = isGoalCommand(draft)
    val canSend = ready && !streaming && draft.isNotBlank() && pendingText == null &&
        (goalCommand || (hasProject && hasProvider) || !hasProvider)
    // Trailing newlines and spaces are noise; the model never wanted them.
    val submit = {
        val text = draftToSend(draft, canSend)
        if (text != null) {
            pendingText = draft
            submittedAt = acceptedMessageCount
            onSend(text)
        }
    }
    // The "/" picker only appears while the first token is still being typed; picking a
    // row just completes the text, and sending is what loads the skill.
    val commandQuery = skillCommandQuery(draft)
    val commandMatches = if (commandQuery == null) emptyList() else matchingSkills(skills, commandQuery)
    var dismissedCommand by rememberSaveable { mutableStateOf<String?>(null) }
    val showCommands = commandQuery != null && dismissedCommand != draft && commandMatches.isNotEmpty()
    LaunchedEffect(commandQuery != null) {
        if (commandQuery != null) onRequestSkills() else dismissedCommand = null
    }
    BackHandler(enabled = showCommands) { dismissedCommand = draft }
    // One container for the whole composer: the field inside carries no outline of its
    // own, and the selector shares the quiet second row with send/stop.
    Column(modifier.fillMaxWidth().imePadding().padding(horizontal = 14.dp, vertical = 9.dp)) {
        if (showCommands) {
            SkillCommandSheet(commandMatches) { skill ->
                dismissedCommand = null
                drafts = withDraft(drafts, sessionId, "/${skill.name} ")
            }
            Spacer(Modifier.padding(bottom = 6.dp))
        }
        Surface(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)),
        ) {
            Column(Modifier.padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 4.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { drafts = withDraft(drafts, sessionId, it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask Rivet…") },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    selector?.invoke()
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (streaming) onCancel() else submit() },
                        enabled = streaming || canSend, modifier = Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(if (streaming || canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant)) {
                        Icon(painterResource(if (streaming) R.drawable.ic_stop else R.drawable.ic_send),
                            stringResource(if (streaming) R.string.chat_stop else R.string.chat_send),
                            tint = if (streaming || canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusFooter(state: ChatUiState, providers: com.kaiser.rivet.ui.provider.ProviderListUiState) {
    val active = providers.configs.firstOrNull { it.id == providers.activeId }
    val modelName = active?.let { "${displaySafeText(it.name)} ${displayModelName(it.model)}" }
    val usage = state.usage
    val turns = footerTurnCount(usage)
    val inputTokens = usage?.reportedInputTokens ?: 0L
    val outputTokens = usage?.reportedOutputTokens ?: 0L
    val totalTokens = inputTokens + outputTokens
    val liveState = when {
        state.streaming -> "Working"
        state.pendingApproval != null -> "Waiting"
        state.activity != null -> "Working"
        else -> "Ready"
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$turns turns",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (totalTokens > 0) {
            Text(
                "${formatTokenCount(totalTokens)} tok",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.spendLabel?.let {
            Text(if (it == "unknown pricing") it else
                "Spend $it" + if (state.spendFromEstimate) " (estimated)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active != null && active.type != ProviderType.Local &&
            state.userPricePerMillionUsd == null &&
            com.kaiser.rivet.agent.PricingTable.price(active.model) == null &&
            state.spendLabel == null) {
            Text("unknown pricing", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.spendEstimate?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (modelName != null) {
            Text(
                modelName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            liveState,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }
}
