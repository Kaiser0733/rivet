package com.kaiser.rivet.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import com.kaiser.rivet.ui.RivetLayout
import com.kaiser.rivet.chat.ContextMeterReading
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kaiser.rivet.chat.ComposerEnter
import com.kaiser.rivet.chat.composerEnter
import com.kaiser.rivet.ui.dismissOnEscape
import com.kaiser.rivet.ui.utilityClick
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import com.kaiser.rivet.ui.panelEntry
import com.kaiser.rivet.ui.rivetMotionSpec
import androidx.compose.ui.draw.clipToBounds
import com.kaiser.rivet.ui.starter.HomeStarters
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import com.kaiser.rivet.ui.RivetMotion
import com.kaiser.rivet.ui.ProtectedControls
import com.kaiser.rivet.ui.entryProgress
import com.kaiser.rivet.ui.messageEntry
import com.kaiser.rivet.ui.motionDisabled
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.workspace.FilePreviewResult
import com.kaiser.rivet.subagent.WorkerRecord
import com.kaiser.rivet.chat.UnsupportedReasoningNotice
import com.kaiser.rivet.chat.commandBusyReason
import com.kaiser.rivet.chat.compactRowEnabled
import com.kaiser.rivet.chat.ComposerAttachment
import com.kaiser.rivet.chat.SharedDraft
import com.kaiser.rivet.chat.draftFor
import com.kaiser.rivet.chat.draftToSend
import com.kaiser.rivet.chat.liveStatusLabel
import com.kaiser.rivet.chat.withDraft
import com.kaiser.rivet.chat.liveStatusLine
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.PresentedFile
import com.kaiser.rivet.agent.SlashCommands
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
import com.kaiser.rivet.ui.FoldReveal
import com.kaiser.rivet.ui.foldChevronRotation
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(chatViewModel: ChatViewModel, providersViewModel: ProvidersViewModel,
               onOpenSettings: () -> Unit, onOpenSidebar: () -> Unit,
               onOpenProjects: () -> Unit,
               onSendFeedback: () -> Unit = {}, onAutonomyChange: (AutonomyMode) -> Unit = {},
               onOpenWorker: ((String) -> Unit)? = null) {
    val state by chatViewModel.uiState.collectAsState()
    val workers by chatViewModel.subagents.collectAsState()
    val skillCatalog by chatViewModel.skillCatalog.collectAsState()
    val providers by providersViewModel.listState.collectAsState()
    val projectBinding = projectBindingState(state.currentSessionId, state.currentSessionWorkspaceId,
        state.projectIdentity, state.projectLoading)
    val wrongProject = projectBinding == ProjectBindingState.Mismatch
    LaunchedEffect(state.ready, state.currentSessionId, state.projectIdentity, state.projectLoading, providers.activeId) {
        if (providers.activeId != null) chatViewModel.openPendingStarter()
    }
    var modelSheetVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var usageLimitDialogVisible by remember { mutableStateOf(false) }
    var usageLimitShownFor by remember { mutableStateOf<String?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<String?>(null) }
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
    val activeProvider = providers.configs.firstOrNull { it.id == providers.activeId }
    val contextReading = activeProvider?.let { config -> state.contextMeter?.takeIf { it.matches(config) } }
    LaunchedEffect(activeProvider, state.currentSessionId, state.ready, state.projectLoading, state.streaming) {
        if (activeProvider != null) chatViewModel.refreshContextMeter(activeProvider)
    }
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
        ProtectedControls {
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
    }

    val canStartChat = state.ready && !state.projectLoading && !state.streaming &&
        !state.undoing && !state.recoveringProjectChanges

    val preview = remember(state.currentSessionId, state.currentSessionWorkspaceId, state.projectIdentity) { PreviewTabs() }
    val linkDoor = if (projectBinding == ProjectBindingState.Matched) {
        remember(preview, state.streaming, state.undoing, state.lastTurnFiles, state.runCheckpoints) {
            val identity = state.currentSessionWorkspaceId
            val session = state.currentSessionId
            AssistantFileLinks(
                identity = identity,
                resolve = { paths -> chatViewModel.existingProjectFiles(paths, identity) },
                load = { path, bounds -> chatViewModel.previewProjectFile(path, identity, bounds) },
                open = { path ->
                    val exists = path in chatViewModel.existingProjectFiles(setOf(path), identity)
                    if (exists && chatViewModel.uiState.value.currentSessionId == session) {
                        preview.open(path)
                        true
                    } else false
                },
            )
        }
    } else null
    val openChatImage = remember(preview) { preview::open }
    ChatWithPreview(preview, load = { path ->
        if (isAppChatImagePath(path)) loadAppChatImage(context, path)
        else chatViewModel.previewProjectFile(path, state.currentSessionWorkspaceId)
    }) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val lane = conversationWidth(maxWidth.value).dp
            val starterRoom = (maxHeight * 0.48f).coerceAtMost(215.dp)
            Column(Modifier.width(lane).fillMaxHeight().align(Alignment.TopCenter)) {
                ChatHeader(
                    title = state.currentSessionTitle ?: state.projectName ?: "Rivet",
                    canStartChat = canStartChat,
                    onOpenSidebar = onOpenSidebar,
                    onNewChat = chatViewModel::newSession,
                )

                val setupNotice = when {
                    state.projectLoading -> "Opening your project…"
                    projectBinding == ProjectBindingState.AccessLost -> PROJECT_ACCESS_LOST_MESSAGE
                    wrongProject -> PROJECT_BINDING_MISMATCH_MESSAGE
                    state.projectError != null -> state.projectError
                    providers.configs.isEmpty() -> "Connect a model in Settings to start."
                    state.projectName == null && !state.streaming -> "Choose a working folder to start."
                    else -> null
                }
                val emptyConversation = state.messages.isEmpty() && !state.streaming && state.error == null &&
                    state.notice == null && !state.interruptedTurn && state.goalLine == null
                if (emptyConversation) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(setupNotice ?: "What do you want to work on?",
                            modifier = Modifier.padding(horizontal = 24.dp),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                } else {
                    setupNotice?.let {
                        Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val visibleState = if (projectBinding == ProjectBindingState.AccessLost &&
                        state.error == PROJECT_ACCESS_LOST_MESSAGE) state.copy(error = null, errorAction = null) else state
                    CompositionLocalProvider(
                        LocalAssistantFileLinks provides linkDoor,
                        LocalOpenChatImage provides openChatImage,
                        LocalOpenTranscriptWorker provides onOpenWorker,
                    ) {
                        key(state.currentSessionId) {
                            MessageList(visibleState, chatViewModel::clearError, onOpenSettings, chatViewModel::retryProjectChanges,
                                onDiscardProjectChanges = { confirmDiscard = state.projectIdentity },
                                onRetryInterruptedTurn = chatViewModel::retryInterruptedTurn,
                                onDismissInterruptedTurn = chatViewModel::dismissInterruptedTurn,
                                onAttachGoal = chatViewModel::attachGoalToProject,
                                onRaiseBudget = chatViewModel::raiseBudgetCapForThisRun,
                                onRetryTurn = chatViewModel::retryFailedTurn,
                                onChooseReasoning = chatViewModel::chooseReasoningLevel,
                                onOpenPreview = preview::open,
                                missingFiles = preview.missing,
                                browser = chatViewModel.browser,
                                workers = workers,
                                modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally))
                        }
                    }
                }
                if (state.undoCheckpointId != null || state.undoing) {
                    ChangeSummary(state, onUndo = { confirmUndo = true },
                        onDismissUndo = chatViewModel::dismissUndoBar,
                        onOpenPreview = preview::open, missingFiles = preview.missing,
                        modifier = Modifier.fillMaxWidth())
                }
                val modelSelector: (@Composable () -> Unit)? =
                    if (providers.configs.isNotEmpty()) {
                        { ProtectedControls { ModelSelector(providersViewModel) { modelSheetVisible = true } } }
                    } else null
                val sharedContent by chatViewModel.sharedContent.collectAsState()
                val composerAttachments by chatViewModel.composerAttachments.collectAsState()
                val composerImports by chatViewModel.composerImports.collectAsState()
                if (emptyConversation) HomeStarters(chatViewModel, canStartChat, state.starterError,
                    modifier = Modifier.heightIn(max = starterRoom).verticalScroll(rememberScrollState()))
                InputBar(streaming = state.streaming,
                    sharedDraft = sharedContent[state.currentSessionId],
                    attachments = composerAttachments[state.currentSessionId].orEmpty(),
                    importingAttachments = state.currentSessionId in composerImports,
                    onStageAttachments = chatViewModel::stageComposerAttachments,
                    onDropAttachment = { name -> chatViewModel.removeComposerAttachment(state.currentSessionId, name) },
                    onPickerUnavailable = chatViewModel::attachmentPickerUnavailable,
                    phoneAvailable = providers.configs.any { it.id == providers.activeId && it.type != ProviderType.Local },
                    ready = state.ready && !state.projectLoading && !state.undoing &&
                        !state.recoveringProjectChanges,
                    hasProject = state.projectName != null && projectBinding == ProjectBindingState.Matched,
                    hasProvider = providers.configs.isNotEmpty(),
                    acceptedMessageCount = state.acceptedMessageCount,
                    commandAckCount = state.commandAckCount,
                    error = state.error, notice = state.notice, sessionId = state.currentSessionId,
                    skills = skillCatalog?.skills.orEmpty(),
                    onRequestSkills = chatViewModel::refreshSkillCatalog,
                    folderName = state.projectName,
                    onOpenProjects = onOpenProjects,
                    selector = modelSelector,
                    contextReading = contextReading,
                    onSend = { chatViewModel.send(it, onSendFeedback, onAutonomyChange) }, onCancel = chatViewModel::cancel,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ChatHeader(title: String, canStartChat: Boolean, onOpenSidebar: () -> Unit,
                       onNewChat: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenSidebar) {
            Icon(painterResource(R.drawable.ic_sidebar), "Open sidebar")
        }
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onNewChat, enabled = canStartChat) {
            Icon(painterResource(R.drawable.ic_new_chat), stringResource(R.string.new_conversation))
        }
    }
}

@Composable
private fun WorkingFolder(name: String?, onOpen: () -> Unit, compact: Boolean = false) {
    if (compact) {
        IconButton(onClick = onOpen) {
            Icon(painterResource(R.drawable.ic_folder), name ?: "Projects", Modifier.size(18.dp))
        }
        return
    }
    TextButton(onClick = onOpen, modifier = Modifier.widthIn(max = 132.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(name ?: "Projects", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium)
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
internal fun MessageList(state: ChatUiState, onDismissError: () -> Unit,
                        onOpenSettings: () -> Unit, onRetryProjectChanges: () -> Unit,
                        onDiscardProjectChanges: () -> Unit,
                        onRetryInterruptedTurn: () -> Unit,
                        onDismissInterruptedTurn: () -> Unit,
                        onAttachGoal: () -> Unit,
                        onRaiseBudget: () -> Unit,
                        onRetryTurn: () -> Unit,
                        onChooseReasoning: (ReasoningLevel) -> Unit,
                        onOpenPreview: (String) -> Unit,
                        missingFiles: Set<String>,
                        browser: com.kaiser.rivet.browser.BrowserSession,
                        modifier: Modifier = Modifier,
                        listState: LazyListState = rememberLazyListState(),
                        workers: List<WorkerRecord> = emptyList()) {
    val browserState by browser.state.collectAsState()
    val showBrowserCard = browserState.chatPreviewVisible(state.currentSessionId)
    val deliverables = remember(state.messages) { presentedFilesForTurns(state.messages) }
    val timeline = remember(state.messages, state.toolLifecycle, state.messageReasoning, workers) {
        val raw = AgentActivityProjection.conversation(state.messages, state.toolLifecycle, workers)
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
                    for (idx in (cursor - 1).coerceAtLeast(0) until state.messages.size) {
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
                    val standalone = matched?.let { index ->
                        splitMessageReasoning(
                            state.messages[index],
                            state.messageReasoning[index],
                        ).forActivity
                    }
                    TimelineDisplayEntry(item, matched, standalone)
                }
                is AgentConversationItem.SystemNotice -> TimelineDisplayEntry(item)
            }
        }
    }
    val liveOperation = state.toolLifecycle.lastOrNull { it.stage in setOf(
        AgentToolLifecycleStage.Queued, AgentToolLifecycleStage.AwaitingApproval,
        AgentToolLifecycleStage.Started,
    ) }
    val streamingText = state.streamingText
    val streamingReasoning = state.streamingReasoning
    var displayedCount by remember(state.currentSessionId) { mutableStateOf(state.acceptedMessageCount) }
    val justSent = remember(state.acceptedMessageCount) { state.acceptedMessageCount > displayedCount }
    SideEffect { displayedCount = state.acceptedMessageCount }
    val messageProgress = entryProgress(state.acceptedMessageCount, justSent, RivetMotion.MESSAGE_MS)
    var hadText by remember(state.currentSessionId) { mutableStateOf(streamingText.isNotBlank()) }
    val textArrived = streamingText.isNotBlank()
    val startsWriting = remember(state.turnStartedAt, textArrived) { textArrived && !hadText }
    SideEffect { hadText = textArrived }
    val answerProgress = entryProgress(state.turnStartedAt to textArrived, startsWriting, RivetMotion.MESSAGE_MS)
    val streaming = state.streaming && state.turnStartedAt != null
    // While a turn runs the status line is always shown, so a long tool call and a
    // stalled stream never look the same. Outside a turn the plain activity text is
    // still used for recovery messages. The clock's start comes from the turn; without one
    // there is no clock to show, and both the item count and the item itself read this one
    // value so they cannot disagree about whether the row exists.
    val liveStatusStart = if (streaming && !state.compacting) state.turnStartedAt else null
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
                isNotice = entry.item is AgentConversationItem.SystemNotice,
            )
        }
    }
    val segmentedTurns = remember(turnItems, streaming, state.turnDurationsMs, state.interruptedTurn, state.error != null) {
        segmentTurnItems(
            items = turnItems,
            isStreaming = streaming,
            turnDurationsMs = state.turnDurationsMs,
            isInterruptedTurn = state.interruptedTurn,
            hasError = state.error != null,
        )
    }
    val transcriptItems = segmentedTurns.sumOf { turn ->
        1 + turn.replyTail.size + deliverables[turn.userItem?.messageIndex].orEmpty().size
    }
    val totalItems = transcriptItems + (if (showBrowserCard) 1 else 0) +
        (if (state.goalLine != null) 1 else 0) +
        (if (streamingReasoning.isNotBlank()) 1 else 0) +
        (if (streamingText.isNotBlank()) 1 else 0) +
        (if (liveStatusStart != null) 1 else 0) +
        (if (showActivity) 1 else 0) +
        (if (state.compacting) 1 else 0) +
        (if (showInterruptedTurn) 1 else 0) +
        (if (state.notice != null) 1 else 0) +
        (if (state.error != null) 1 else 0) +
        (if (state.unsupportedReasoning != null) 1 else 0)
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var lastAcceptedCount by rememberSaveable { mutableStateOf(state.acceptedMessageCount) }
    var userScrolling by remember { mutableStateOf(false) }
    var programmaticScroll by remember { mutableStateOf(false) }
    val bottomSlop = with(LocalDensity.current) { 72.dp.toPx() }
    val nearBottom by remember(bottomSlop) { derivedStateOf {
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
        if (last.index < layout.totalItemsCount - 1) false
        else layout.viewportEndOffset - (last.offset + last.size) >= -bottomSlop
    } }
    val transcriptScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrolling = true
                    followBottom = false
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { Triple(listState.isScrollInProgress, programmaticScroll, userScrolling) }
            .collect { (scrolling, programmatic, manual) ->
                if (scrolling && !programmatic && !manual) {
                    userScrolling = true
                    followBottom = false
                } else if (!scrolling && manual) {
                    followBottom = !listState.canScrollForward
                    userScrolling = false
                }
            }
    }
    // Sending a message is an explicit request to see it: after reading back through the
    // conversation, the reply and the user's own message must not land off-screen.
    LaunchedEffect(state.acceptedMessageCount) {
        if (lastAcceptedCount != state.acceptedMessageCount) {
            lastAcceptedCount = state.acceptedMessageCount
            followBottom = true
        }
    }
    // Following the bottom while the reader is there. Two things move the list: a row appearing,
    // which this effect lands exactly, and a row growing — and growth is a layout event, not a
    // character count. A wrapped line adds a line height inside the same character bucket, so the
    // character-bucketed trigger this replaced missed it and left the live-status row half below
    // the list's edge until the next bucket, or for good if the stream stalled there (device
    // finding, kaiser, 2026-11-02). Growth is followed by the settle effect below; this one only
    // has to handle rows appearing or disappearing, which move everything at once.
    LaunchedEffect(segmentedTurns.size, streamingReasoning.isNotBlank(), streamingText.isNotBlank(),
        state.streaming, state.activity, state.toolLifecycle, state.interruptedTurn, state.error,
        state.notice, showBrowserCard, state.compacting, totalItems, state.acceptedMessageCount) {
        if (followBottom && totalItems > 0 && !userScrolling && !listState.isScrollInProgress) {
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
    // end, so the live-status row keeps the padding under it.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow 0
            last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
        }.collect { gap ->
            if (!followBottom || gap <= 0 || userScrolling || listState.isScrollInProgress) return@collect
            programmaticScroll = true
            try {
                listState.scrollBy(gap.toFloat())
            } finally {
                programmaticScroll = false
            }
        }
    }
    val jumpScope = rememberCoroutineScope()
    Box(modifier.fillMaxSize()) {
        PlatformSelectionToolbar {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(transcriptScrollConnection),
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
                    segmentedTurns.forEach { turn ->
                        item(key = turn.turnIndex) {
                            TurnBlockView(
                                turn = turn,
                                state = state,
                                isLastTurn = turn.turnIndex == segmentedTurns.lastIndex,
                                messageProgress = if (turn.turnIndex == segmentedTurns.lastIndex) messageProgress else 1f,
                            )
                        }
                        turn.replyTail.forEachIndexed { index, part ->
                            item(key = "reply-${turn.turnIndex}-$index") { TurnTextPart(part, state) }
                        }
                        items(deliverables[turn.userItem?.messageIndex].orEmpty()) { file ->
                            if (file.inlineImage) {
                                val links = LocalAssistantFileLinks.current
                                val image = ChatMarkdownBlock.Image(file.description, file.path)
                                if (isAppChatImagePath(file.path)) {
                                    BrowserImage(image, "Screenshot unavailable.", inline = true)
                                } else if (links != null) {
                                    ProjectInlineImage(image, file.path, links, onOpenPreview)
                                }
                            } else {
                                ProjectFileCard(PresentedFile(file.path, file.description),
                                    missing = file.path in missingFiles, onOpen = { onOpenPreview(file.path) })
                            }
                        }
                    }
                    if (showBrowserCard) item(key = "live-browser") {
                        LiveBrowserCard(browser, browserState)
                    }
                    if (streamingReasoning.isNotBlank()) item(key = "streaming-thinking") {
                        ReasoningRow(
                            streamingReasoning, streaming = true, id = "streaming",
                            showWaitFallback = statusLabel == "Thinking" && state.activity == "Working on it…",
                        )
                    }
                    if (streamingText.isNotBlank()) item(key = "streaming-assistant") {
                        Box(Modifier.messageEntry(answerProgress)) {
                            AssistantMessage(displaySafeText(streamingText), streaming = true)
                        }
                    }
                    liveStatusStart?.let { start ->
                        item(key = "live-status") { LiveStatusRow(statusLabel, start) }
                    }
                    if (state.compacting) item(key = "manual-compaction") { CompactingRow() }
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
        }
        if (totalItems > 0 && listState.canScrollForward && (!followBottom || !nearBottom)) {
            Surface(
                onClick = {
                    jumpScope.launch {
                        userScrolling = false
                        followBottom = true
                        programmaticScroll = true
                        try {
                            scrollToConversationBottom(listState, totalItems - 1)
                        } finally {
                            programmaticScroll = false
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text("Jump to latest", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
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
private fun TurnBlockView(
    turn: SegmentedTurn<TimelineDisplayEntry>,
    state: ChatUiState,
    isLastTurn: Boolean,
    messageProgress: Float,
) {
    val lastAnswer = turn.answerItem?.item as? AgentConversationItem.Message
    val hasAnswer = lastAnswer?.message?.text?.isNotBlank() == true
    val outcome = FoldOutcomeObservation(
        turnStartedAt = state.turnStartedAt.takeIf { isLastTurn && turn.userItem != null },
        acceptedMessageCount = state.acceptedMessageCount,
        spec = turn.spec,
        cleanFinish = hasAnswer && state.turnStartedWallClock == null &&
            state.run?.phase !in setOf(RunPhase.Paused, RunPhase.Stopped, RunPhase.Failed),
    )
    var observedOutcome by remember { mutableStateOf<FoldOutcomeObservation?>(null) }
    val revealOutcome = outcome.revealsAfter(observedOutcome)
    var completion by remember(outcome.turnStartedAt, outcome.acceptedMessageCount) { mutableStateOf(false) }
    SideEffect {
        observedOutcome = outcome
        if (revealOutcome) completion = true
    }
    val finishProgress = entryProgress(completion, completion, RivetMotion.COMPLETE_MS)
    val finishInk = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().drawBehind {
            if (finishProgress > 0f && finishProgress < 1f) {
                val length = RivetMotion.completionLength.toPx().coerceAtMost(size.width)
                val reach = (finishProgress * 1.75f).coerceAtMost(1f)
                val strength = (1f - (finishProgress - 0.52f).coerceAtLeast(0f) / 0.48f)
                val y = size.height - RivetMotion.completionStroke.toPx() / 2f
                drawLine(finishInk.copy(alpha = strength), Offset(0f, y), Offset(length * reach, y),
                    RivetMotion.completionStroke.toPx(), StrokeCap.Round)
            }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        turn.userItem?.let { entry ->
            if (entry.item is AgentConversationItem.Message) {
                Box(Modifier.messageEntry(messageProgress)) {
                    MessageRow(
                        message = entry.item.message,
                        reasoning = entry.messageIndex?.let { state.messageReasoning[it] },
                        messageKey = entry.messageIndex ?: entry.item.message.hashCode(),
                    )
                }
            }
        }

        turn.processBody.forEachIndexed { index, part ->
            when (part) {
                is TurnPart.Process -> {
                    val groups = part.items.mapNotNull { (it.item as? AgentConversationItem.Activity)?.group }
                    val thinking = part.items.mapNotNull { it.standaloneThinking }
                    val isTail = index == turn.parts.lastIndex
                    if (groups.isNotEmpty()) {
                        key(groups.first().id) {
                            ActivityRow(
                                groups = groups,
                                thinking = thinking,
                                isLive = turn.spec.isLive && isTail,
                                turnOutcome = when {
                                    turn.spec.isLive || index != turn.processBody.lastIndex -> null
                                    turn.spec.isError -> "Failed"
                                    isLastTurn && turn.spec.isStopped && state.runRecovery != null &&
                                        state.run?.phase == RunPhase.Paused ->
                                        state.run.plainState().replaceFirstChar { it.titlecase() }
                                    turn.spec.isStopped -> "Stopped"
                                    hasAnswer && turn.spec.durationMs != null -> turn.spec.foldLabel()
                                    else -> null
                                },
                            )
                        }
                    }
                }
                is TurnPart.Answer, is TurnPart.Notice -> TurnTextPart(part, state)
            }
        }

        TranscriptWorkerRows(turn.processItems.flatMap { entry ->
            (entry.item as? AgentConversationItem.Activity)?.group?.operations.orEmpty()
        })
    }
}

@Composable
private fun TurnTextPart(part: TurnPart<TimelineDisplayEntry>, state: ChatUiState) {
    when (part) {
        is TurnPart.Answer -> {
            val entry = part.item
            val item = entry.item as? AgentConversationItem.Message ?: return
            val owned = splitMessageReasoning(item.message, entry.messageIndex?.let { state.messageReasoning[it] })
            MessageRow(
                message = item.message,
                reasoning = owned.forAnswer,
                messageKey = entry.messageIndex ?: item.message.hashCode(),
            )
        }
        is TurnPart.Notice -> (part.item.item as? AgentConversationItem.SystemNotice)?.let {
            SystemNoticeRow(it.text)
        }
        is TurnPart.Process -> Unit
    }
}

@Composable
private fun SystemNoticeRow(text: String) {
    Text(
        displaySafeText(text),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ProcessGroupHeader(
    title: String,
    activityKind: ProcessActivityKind,
    open: Boolean,
    isLive: Boolean,
    onToggle: () -> Unit,
    turnOutcome: String?,
) {
    val rotation = foldChevronRotation(open)
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
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
            color = if (isLive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (turnOutcome != null) {
            Text(turnOutcome, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ActivityRow(
    groups: List<AgentActivityGroup>,
    thinking: List<String> = emptyList(),
    isLive: Boolean = false,
    turnOutcome: String? = null,
) {
    val groupKey = groups.first().id
    var expanded by rememberSaveable(groupKey) { mutableStateOf(false) }

    val operations = remember(groups) { groups.flatMap { it.operations } }
    val summary = remember(operations, thinking, isLive) {
        processGroupSummary(operations, thinking, closed = !isLive)
    }
    val counts = summary.counts
    val title = if (isLive && summary.running != null) summary.liveLabel() else summary.title()
    val primaryKind = counts.firstOrNull()?.kind ?: ProcessActivityKind.Thinking

    val config = LocalConfiguration.current
    val maxBodyHeight = minOf(400.dp, (config.screenHeightDp * 0.5f).dp)

    Column(Modifier.fillMaxWidth()) {
        ProcessGroupHeader(
            title = title,
            activityKind = primaryKind,
            open = expanded,
            isLive = isLive,
            onToggle = { expanded = !expanded },
            turnOutcome = turnOutcome,
        )
        FoldReveal(expanded = expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                thinking.forEachIndexed { index, text ->
                    ReasoningRow(
                        text,
                        streaming = false,
                        id = "thinking-$groupKey-$index",
                    )
                }
                operations.forEach { operation -> ActivityOperationRow(operation, compact = true) }
            }
        }
    }
}

internal val LocalOpenTranscriptWorker = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun ActivityOperationRow(operation: AgentActivityOperation, compact: Boolean = false) {
    val open = LocalOpenTranscriptWorker.current
    val workerId = operation.workerId
    val openWorker = if (open != null && workerId != null) {
        Modifier.clickable(onClickLabel = "Open worker") { open(workerId) }
    } else Modifier
    if (workerId != null && !compact) {
        TranscriptWorkerDetails(operation, Modifier.fillMaxWidth().then(openWorker))
        return
    }
    Column(Modifier.fillMaxWidth().then(openWorker)) {
        val outcome = activityOutcomeText(operation.outcome)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val target = displaySafeText(operation.detail).lineSequence().firstOrNull().orEmpty()
            Text(
                if (target.isBlank()) operation.title else "${operation.title} · $target",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(outcome,
                style = MaterialTheme.typography.labelSmall, color = when (operation.outcome) {
                    ActivityOutcome.Failed, ActivityOutcome.Denied, ActivityOutcome.Blocked -> MaterialTheme.colorScheme.error
                    ActivityOutcome.Running, ActivityOutcome.WaitingApproval -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
        }
        operation.outcomeDetail?.takeUnless { it == outcome }?.let {
            Text(displaySafeText(it).lineSequence().first(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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

@Composable
internal fun MessageRow(message: AgentMessage, reasoning: String? = null, messageKey: Int = 0) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.role == AgentRole.Assistant && !reasoning.isNullOrBlank()) {
            ReasoningRow(reasoning, streaming = false, id = "thinking-$messageKey")
        }
        if (message.role == AgentRole.User) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    modifier = Modifier.widthIn(max = 525.dp).fillMaxWidth(0.82f),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        message.imagePaths.forEach { path ->
                            BrowserImage(ChatMarkdownBlock.Image("Shared image", path),
                                unavailable = "Shared image no longer available.")
                        }
                    }
                }
            }
        } else {
            AssistantMessage(displaySafeText(message.text), streaming = false)
        }
    }
}

// Assistant reasoning row copied from dsh ReasoningRow: collapsed is exactly one 24dp line
// ("Think" + 2dp dot + 13sp preview without asterisks).
// Expanded body is indented 22dp with secondary typography, no Surface and no Show/Hide buttons.
@Composable
private fun ReasoningRow(
    text: String,
    streaming: Boolean,
    id: String,
    modifier: Modifier = Modifier,
    showWaitFallback: Boolean = false,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val rotation = foldChevronRotation(expanded)
    val scan = remember { PreviewScan() }
    val summaryText = remember(text, streaming) {
        val raw = displaySafeText(thinkingPreview(text, streaming, scan))
        raw.replace("**", "").trim()
    }

    val waitingWithoutPreview = showWaitFallback && summaryText.isEmpty()

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
                text = if (waitingWithoutPreview) "Waiting for a response" else "Think",
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
                    color = if (streaming) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        FoldReveal(expanded = expanded) {
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
private fun BrowserImage(
    image: ChatMarkdownBlock.Image,
    unavailable: String,
    inline: Boolean = false,
) {
    val context = LocalContext.current
    val openImage = LocalOpenChatImage.current
    val preview by key(context, image.source, inline) {
        produceState<ChatImageState>(ChatImageState.Pending) {
            loadChatImage { bounds ->
                loadAppChatImage(context, image.source, maxEdge = if (inline) 1280 else 1536,
                    onImageBounds = bounds)
            }
        }
    }
    val description = displaySafeText(image.alt).ifBlank { "Browser screenshot" }
    ReservedChatImage(preview, description, open = if (inline) {
        openImage?.let { open -> { open(image.source) } }
    } else null, inline = inline) {
        Text(unavailable, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MarkdownTableCell(text: String, header: Boolean) {
    Text(displaySafeText(text),
        modifier = Modifier.widthIn(min = 88.dp, max = 160.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = if (header) FontWeight.SemiBold else null,
        ))
}

@Composable
private fun MarkdownTable(table: ChatMarkdownBlock.Table) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                table.header.forEach { MarkdownTableCell(it, header = true) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            table.rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { MarkdownTableCell(it, header = false) }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(text: String, streaming: Boolean) {
    val decoded = remember(text) { com.kaiser.rivet.chat.decodeEntities(text) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (streaming) {
            Text(streamingAssistantText(decoded), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default))
        } else AssistantMarkdown(decoded)
    }
}

@Composable
internal fun AssistantMarkdown(text: String, showInlineImages: Boolean = true) {
    // The answer grows a few characters at a time while it streams. A full parse per publication
    // re-splits and re-joins the whole reply; this keeps the blocks before the last one and
    // re-parses only that block, with the same result (see MarkdownBlocks).
    val parser = remember { MarkdownBlocks() }
    val parsed = parser.forText(text)
    val blocks = remember(parsed) { parsed.flatMap { block ->
        if (block is ChatMarkdownBlock.Paragraph) splitParagraphImages(block.text) else listOf(block)
    } }
    val links = LocalAssistantFileLinks.current
    val paths = remember(blocks, links != null) {
        if (links == null) emptySet() else fileReferencePaths(blocks)
    }
    var files by remember(links, paths) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(links, paths) {
        if (links != null && paths.isNotEmpty()) files = links.resolve(paths)
    }
    val linkScope = rememberCoroutineScope()
    val open: ((String) -> Unit)? = if (links == null) null else remember(links, paths) {
        { path ->
            linkScope.launch { if (!links.open(path)) files = files - path }
            Unit
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ChatMarkdownBlock.Paragraph -> InlineMarkdown(block.text, files = files, open = open)
                is ChatMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    InlineMarkdown(block.text, style = style.copy(fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold), files = files, open = open)
                }
                is ChatMarkdownBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp),
                ) { InlineMarkdown(block.text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    files = files, open = open) }
                is ChatMarkdownBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(if (block.ordered) "${block.start + index}." else "•",
                                modifier = Modifier.widthIn(min = 24.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default))
                            InlineMarkdown(item, modifier = Modifier.weight(1f), files = files, open = open)
                        }
                    }
                }
                is ChatMarkdownBlock.Image -> {
                    val path = projectImagePath(block.source)
                    when {
                        !showInlineImages -> if (block.alt.isNotBlank()) Text(displaySafeText(block.alt))
                        path != null && links != null && open != null ->
                            ProjectInlineImage(block, path, links, open)
                        isAppChatImagePath(block.source) ->
                            BrowserImage(block, unavailable = block.unavailableText(), inline = true)
                        else -> Text(block.unavailableText())
                    }
                }
                is ChatMarkdownBlock.Table -> MarkdownTable(block)
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
private fun ProjectInlineImage(image: ChatMarkdownBlock.Image, path: String, links: AssistantFileLinks,
                               onOpen: (String) -> Unit) {
    val preview by key(links.identity, path) {
        produceState<ChatImageState>(ChatImageState.Pending, links) {
            loadChatImage { bounds -> links.load(path, bounds) }
        }
    }
    ReservedChatImage(preview, displaySafeText(image.alt).ifBlank { "Project image" },
        open = { onOpen(path) }) {
        Text(image.unavailableText(), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun ChatMarkdownBlock.Image.unavailableText(): String =
    displaySafeText(alt).ifBlank { "Image unavailable." }

@Composable
private fun InlineMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    files: Set<String> = emptySet(),
    open: ((String) -> Unit)? = null,
) {
    val textStyle = style ?: MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)
    val runs = remember(text) { parseInlineMarkdown(text) }
    val color = MaterialTheme.colorScheme.onBackground
    val codeBackground = MaterialTheme.colorScheme.surfaceContainer
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(runs, codeBackground, files, linkColor, open) {
        if (open == null) assistantInlineText(runs, codeBackground)
        else assistantInlineText(runs, codeBackground, files, linkColor, open)
    }
    if (annotated.spanStyles.isEmpty()) {
        Text(annotated.text, modifier = modifier, style = textStyle.copy(color = color))
    } else {
        Text(annotated, modifier = modifier, style = textStyle.copy(color = color))
    }
}

internal fun approvalTitle(request: AgentApprovalRequest): String = when {
    request.call.name == "run_command" -> "Run a project command?"
    request.dangerous -> request.title
    request.title.trimEnd().endsWith("?") -> request.title
    else -> "Allow Rivet to ${request.title.lowercase()}?"
}

@Composable
internal fun ApprovalDialog(request: AgentApprovalRequest, onApprove: (Long) -> Unit,
                            onDeny: (Long) -> Unit, onStop: (Long) -> Unit = {}) {
    val command = request.call.name == "run_command"
    val delete = request.call.name == "delete_path"
    // A tap outside or Back keeps the card pending; only the buttons answer for the user.
    AlertDialog(onDismissRequest = {
        if (approvalResponse(ApprovalEvent.Dismiss) == ApprovalResponse.Deny) onDeny(request.approvalToken)
    },
        title = { Text(displaySafeText(approvalTitle(request)),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        dismissButton = {
            Row {
                TextButton(onClick = { onStop(request.approvalToken) }) { Text("Stop turn") }
                TextButton(onClick = { onDeny(request.approvalToken) }) {
                    Text(if (delete) "Cancel" else "Don't allow")
                }
            }
        })
}

@Composable
private fun ChangeSummary(state: ChatUiState, onUndo: () -> Unit,
                          onDismissUndo: () -> Unit, onOpenPreview: (String) -> Unit,
                          missingFiles: Set<String>, modifier: Modifier = Modifier) {
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
        if (details) state.lastTurnFiles.forEach { path ->
            ProjectFileCard(PresentedFile(path, path.substringBeforeLast('/', "")),
                missing = path in missingFiles, onOpen = { onOpenPreview(path) })
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
                spendLabel?.takeUnless { it == "unknown pricing" }?.let {
                    Text("Spend $it",
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

internal fun isGoalCommand(text: String): Boolean = SlashCommands.builtIn(text) == SlashAction.Goal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(streaming: Boolean, ready: Boolean, acceptedMessageCount: Long,
                     commandAckCount: Long = 0,
                     hasProject: Boolean = true, hasProvider: Boolean = true,
                     error: String?, notice: String?, sessionId: String?,
                     sharedDraft: SharedDraft? = null,
                     attachments: List<ComposerAttachment>,
                     importingAttachments: Boolean,
                     onStageAttachments: (String?, List<android.net.Uri>, Boolean, File?) -> Unit,
                     onDropAttachment: (String) -> Unit,
                     onPickerUnavailable: () -> Unit,
                     phoneAvailable: Boolean = false,
                     skills: List<Skill> = emptyList(),
                     onRequestSkills: suspend () -> Unit = {},
                     folderName: String?,
                     onOpenProjects: () -> Unit,
                     selector: (@Composable () -> Unit)? = null,
                     contextReading: ContextMeterReading? = null,
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
    var attachmentsOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    val pickAttachment = rememberComposerPickers(sessionId, onStageAttachments, onPickerUnavailable)
    var appliedShares by rememberSaveable(stateSaver = DRAFT_SAVER) {
        mutableStateOf(emptyMap<String, String>())
    }
    LaunchedEffect(sharedDraft?.id) {
        if (sharedDraft != null && sharedDraft.id != draftFor(appliedShares, sessionId)) {
            val current = draftFor(drafts, sessionId)
            drafts = withDraft(drafts, sessionId,
                if (current.isBlank()) sharedDraft.text else "$current\n\n${sharedDraft.text}")
            appliedShares = withDraft(appliedShares, sessionId, sharedDraft.id)
        }
    }
    var pendingText by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedAt by rememberSaveable { mutableStateOf(0L) }
    val ackCount = acceptedMessageCount + commandAckCount
    LaunchedEffect(ackCount, streaming, error, notice, sessionId) {
        if (pendingText != null && ackCount > submittedAt) {
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
    val builtInCommand = SlashCommands.builtIn(draft) != null
    fun canSubmit(text: String, builtIn: Boolean): Boolean =
        ready && !streaming && !importingAttachments && (text.isNotBlank() || attachments.isNotEmpty()) && pendingText == null &&
            (builtIn || ((hasProject || sharedDraft != null || attachments.isNotEmpty() || phoneAvailable) && hasProvider) ||
                !hasProvider)
    val canSend = canSubmit(draft, builtInCommand)
    val submit = {
        val latest = draftFor(drafts, sessionId)
        val allowed = canSubmit(latest, SlashCommands.builtIn(latest) != null)
        val text = if (allowed && attachments.isNotEmpty() && latest.isBlank()) "" else draftToSend(latest, allowed)
        if (text != null) {
            pendingText = latest
            submittedAt = ackCount
            onSend(text)
        }
    }
    // The "/" picker only appears while the first token is still being typed; picking a
    // row just completes the text, and sending is what loads the skill.
    val commandQuery = skillCommandQuery(draft)
    val commandMatches = if (commandQuery == null) emptyList()
        else slashRows(skills, commandQuery, compactEnabled = compactRowEnabled(streaming))
    var dismissedCommand by rememberSaveable { mutableStateOf<String?>(null) }
    val showCommands = commandQuery != null && dismissedCommand != draft && commandMatches.isNotEmpty()
    LaunchedEffect(commandQuery != null) {
        if (commandQuery != null) onRequestSkills() else dismissedCommand = null
    }
    BackHandler(enabled = showCommands) { dismissedCommand = draft }
    var field by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    val editing = if (field.text == draft) field else TextFieldValue(draft, TextRange(draft.length))
    val updateField: (TextFieldValue) -> Unit = {
        field = it
        drafts = withDraft(drafts, sessionId, it.text)
    }
    var focused by remember { mutableStateOf(false) }
    val composerEdge by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = rivetMotionSpec(RivetMotion.FOCUS_MS),
        label = "Composer focus",
    )
    val sendInteraction = remember { MutableInteractionSource() }
    val pressingSend by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(if (pressingSend) 0.91f else 1f,
        animationSpec = rivetMotionSpec(RivetMotion.FOCUS_MS), label = "Send press")
    // One container for the whole composer: the field inside carries no outline of its
    // own, and the selector shares the quiet second row with send/stop.
    Column(modifier.fillMaxWidth().imePadding().padding(horizontal = 14.dp, vertical = 9.dp)
        .dismissOnEscape(showCommands) { dismissedCommand = draft }) {
        if (showCommands) {
            val progress = entryProgress(Unit, animate = true, duration = RivetMotion.PAGE_MS)
            Box(Modifier.clipToBounds()) {
                Box(Modifier.panelEntry(progress)) {
                    SlashCommandPanel(commandMatches) { row ->
                        dismissedCommand = null
                        val nextDraft = when (row.action) {
                            SlashAction.Goal -> "/goal "
                            null -> row.skill?.let { "/${it.name} " } ?: draft
                            else -> ""
                        }
                        drafts = withDraft(drafts, sessionId, nextDraft)
                        row.action?.takeUnless { it == SlashAction.Goal }?.let { onSend("/${it.token}") }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 6.dp))
        }
        Surface(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, composerEdge),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val contextFitsInRow = maxWidth >= RivetLayout.contextInlineWidth
                val compactControls = maxWidth < RivetLayout.compactComposerWidth
                val composerInset = 4.dp
                Column(Modifier.padding(start = composerInset, end = 6.dp, top = 6.dp, bottom = 4.dp)) {
                    if (attachments.isNotEmpty()) {
                        ComposerAttachmentStrip(attachments, sessionId, !streaming, onDropAttachment)
                    }
                    sharedDraft?.imageName?.let { name ->
                        Text("Attached image · $name", modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedTextField(
                        value = editing,
                        onValueChange = updateField,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                                val native = event.nativeKeyEvent
                                val action = composerEnter(
                                    hardware = native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD == 0,
                                    shift = event.isShiftPressed,
                                    shortcutModifier = event.isCtrlPressed || event.isMetaPressed || event.isAltPressed,
                                )
                                if (action == ComposerEnter.Edit) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) {
                                    when (action) {
                                        ComposerEnter.Send -> submit()
                                        ComposerEnter.Newline -> {
                                            val from = editing.selection.min
                                            val to = editing.selection.max
                                            updateField(TextFieldValue(editing.text.replaceRange(from, to, "\n"),
                                                TextRange(from + 1)))
                                        }
                                        ComposerEnter.Edit -> Unit
                                    }
                                }
                                true
                            },
                        placeholder = { Text("Ask Rivet…") },
                        minLines = 1,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
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
                    if (!contextFitsInRow && contextReading != null) {
                        ConversationContextMeter(contextReading, Modifier.align(Alignment.End).padding(end = 6.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { attachmentsOpen = true },
                                enabled = ready && !streaming && !importingAttachments) {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = "Attachments")
                            }
                            if (attachmentsOpen) {
                                AttachmentSheet(composerInset, onDismiss = { attachmentsOpen = false },
                                    onPick = pickAttachment)
                            }
                        }
                        Box(Modifier.weight(1f).padding(end = 4.dp)) {
                            selector?.invoke()
                        }
                        if (contextFitsInRow && contextReading != null) {
                            ConversationContextMeter(contextReading, Modifier.padding(horizontal = 4.dp))
                        }
                        if (compactControls) WorkingFolder(folderName, onOpenProjects, compact = true)
                        else WorkingFolder(folderName, onOpenProjects)
                        IconButton(onClick = { if (streaming) onCancel() else submit() },
                            enabled = streaming || canSend, interactionSource = sendInteraction,
                            modifier = Modifier.size(48.dp).graphicsLayer {
                                scaleX = sendScale
                                scaleY = sendScale
                            }.clip(CircleShape)
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
}
