package com.kaiser.rivet.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.agent.AgentApprovalRequest
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
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.history.HistoryPane
import com.kaiser.rivet.runtime.ManagedProcessStatus
import kotlinx.coroutines.flow.collect

@Composable
fun ChatScreen(chatViewModel: ChatViewModel, providersViewModel: ProvidersViewModel,
               onOpenSettings: () -> Unit, onOpenHistory: () -> Unit, onOpenProcesses: () -> Unit) {
    val landscape = showsHistoryPane(LocalConfiguration.current.orientation)
    val state by chatViewModel.uiState.collectAsState()
    val providers by providersViewModel.listState.collectAsState()
    val processItems by chatViewModel.managedProcesses.processes.collectAsState()
    val hasActiveProcesses = processItems.any { it.status in setOf(
        ManagedProcessStatus.Starting, ManagedProcessStatus.Running, ManagedProcessStatus.Stopping,
    ) }
    val projectBinding = projectBindingState(state.currentSessionId, state.currentSessionWorkspaceId,
        state.projectIdentity, state.projectLoading)
    val wrongProject = projectBinding == ProjectBindingState.Mismatch
    var pendingProject by remember { mutableStateOf<Pair<Uri, Int>?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
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
    state.pendingApproval?.let { approval ->
        ApprovalDialog(approval, chatViewModel::approve, chatViewModel::deny)
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        val sidebarWidth = landscapeSidebarWidth(maxWidth.value).dp
        Row(Modifier.fillMaxSize()) {
            if (landscape) {
                HistoryPane(chatViewModel, Modifier.width(sidebarWidth).fillMaxSize())
                VerticalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f))
            }
            // Keep one Chat subtree so the composer and scroll state survive rotation.
            Column(Modifier.weight(1f).fillMaxSize()) {
                Row(Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (landscape) Spacer(Modifier.width(96.dp))
                    else {
                        IconButton(onClick = onOpenHistory, modifier = Modifier.size(48.dp),
                            enabled = state.ready && !state.projectLoading && !state.streaming &&
                                !state.undoing && !state.recoveringProjectChanges) {
                            Icon(painterResource(R.drawable.ic_history), "Conversation history",
                                tint = MaterialTheme.colorScheme.onBackground)
                        }
                        Spacer(Modifier.width(48.dp))
                    }
                    TextButton(onClick = ::chooseProject, modifier = Modifier.weight(1f),
                        enabled = providers.configs.isNotEmpty() && state.ready && !state.streaming &&
                            !state.projectLoading && !state.undoing && !state.recoveringProjectChanges) {
                        Icon(painterResource(R.drawable.ic_files), null, Modifier.size(18.dp))
                        Text(displaySafeText(state.projectName ?: "Choose project"), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp))
                    }
                    IconButton(onClick = onOpenProcesses, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_processes), "Processes",
                            tint = if (hasActiveProcesses) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
                if (providers.configs.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth()) {
                        ModelSelector(Modifier.align(Alignment.Center), providersViewModel)
                    }
                }
                HorizontalDivider(Modifier.fillMaxWidth()
                    .align(Alignment.CenterHorizontally).padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.23f))
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
                        "Add a provider first. Then choose the project you want Rivet to work on.",
                        "Open Settings", onOpenSettings, Modifier.weight(1f))
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
                } else if (state.messages.isEmpty() && !state.streaming && state.error == null && state.notice == null) {
                    EmptyState(RivetDoodle.Ready, "Tell Rivet what you want to change.",
                        "Rivet can inspect this project and will ask before changing files.",
                        null, {}, Modifier.weight(1f))
                } else {
                    val visibleState = if (projectBinding == ProjectBindingState.AccessLost &&
                        state.error == PROJECT_ACCESS_LOST_MESSAGE) state.copy(error = null, errorAction = null) else state
                    key(state.currentSessionId) {
                        MessageList(visibleState, chatViewModel::clearError, onOpenSettings, chatViewModel::retryProjectChanges,
                            Modifier.weight(1f).align(Alignment.CenterHorizontally))
                    }
                }
                if (state.undoCheckpointId != null || state.undoing) {
                    ChangeSummary(state, onUndo = { confirmUndo = true },
                        modifier = Modifier.fillMaxWidth())
                }
                InputBar(streaming = state.streaming, ready = state.ready && state.projectName != null &&
                    projectBinding == ProjectBindingState.Matched &&
                    providers.configs.isNotEmpty() && !state.projectLoading && !state.undoing && !state.recoveringProjectChanges,
                    acceptedMessageCount = state.acceptedMessageCount,
                    error = state.error, notice = state.notice,
                    onSend = chatViewModel::send, onCancel = chatViewModel::cancel,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EmptyState(kind: RivetDoodle, title: String, detail: String, action: String?,
                       onAction: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 390.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RivetDoodleMark(kind)
            Text(title, style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge)
            if (action != null) RivetOutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ModelSelector(modifier: Modifier, providersViewModel: ProvidersViewModel) {
    val state by providersViewModel.listState.collectAsState()
    val active = state.configs.firstOrNull { it.id == state.activeId }
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { menu = true }) {
            Text(active?.let { "${it.name} · ${it.model}" } ?: "Choose model",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(painterResource(R.drawable.ic_chevron_down), stringResource(R.string.model_switch))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            state.configs.forEach { config ->
                DropdownMenuItem(text = { Text("${config.name} · ${config.model}") }, onClick = {
                    providersViewModel.setActive(config.id); menu = false
                })
            }
        }
    }
}

@Composable
private fun MessageList(state: ChatUiState, onDismissError: () -> Unit,
                        onOpenSettings: () -> Unit, onRetryProjectChanges: () -> Unit,
                        modifier: Modifier = Modifier) {
    val timeline = remember(state.messages, state.toolLifecycle) {
        AgentActivityProjection.conversation(state.messages, state.toolLifecycle)
    }
    val liveOperation = state.toolLifecycle.lastOrNull { it.stage in setOf(
        AgentToolLifecycleStage.Queued, AgentToolLifecycleStage.AwaitingApproval,
        AgentToolLifecycleStage.Started,
    ) }
    val streamingText = state.streamingText
    val showActivity = state.activity != null && streamingText.isBlank() && liveOperation == null
    val totalItems = timeline.size +
        (if (streamingText.isNotBlank()) 1 else 0) +
        (if (showActivity) 1 else 0) +
        (if (state.notice != null) 1 else 0) +
        (if (state.error != null) 1 else 0)
    val listState = rememberLazyListState()
    var followBottom by remember { mutableStateOf(true) }
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
    LaunchedEffect(timeline.size, streamingText.length, state.streaming, state.activity,
        state.toolLifecycle, state.error, state.notice) {
        if (followBottom && totalItems > 0) {
            programmaticScroll = true
            try {
                scrollToConversationBottom(listState, totalItems - 1)
            } finally {
                programmaticScroll = false
            }
        }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(timeline) { item ->
            when (item) {
                is AgentConversationItem.Message -> MessageRow(item.message)
                is AgentConversationItem.Activity -> ActivityRow(item.group)
            }
        }
        if (streamingText.isNotBlank()) item(key = "streaming-assistant") {
            AssistantMessage(displaySafeText(streamingText), streaming = true)
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
                    null -> null
                },
                when (state.errorAction) {
                    ChatErrorAction.OpenSettings -> onOpenSettings
                    ChatErrorAction.RetryProjectChanges -> onRetryProjectChanges
                    null -> null
                })
        } }
    }
}

@Composable
private fun ActivityRow(group: AgentActivityGroup) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Activity", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(group.summary, Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(if (expanded) "Hide" else "Details", Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                group.operations.forEach { operation -> ActivityOperationRow(operation) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
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
    val viewport = layout.viewportEndOffset - layout.viewportStartOffset
    val offset = item.size - viewport
    if (offset != 0) state.scrollToItem(index, offset)
}

@Composable
private fun MessageRow(message: AgentMessage) {
    if (message.role == AgentRole.User) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = 16.dp, bottomEnd = 4.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f))) {
                    Text(message.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    } else {
        AssistantMessage(displaySafeText(message.text), streaming = false)
    }
}

@Composable
private fun AssistantMessage(text: String, streaming: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.94f),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Rivet", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            if (streaming) {
                Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default))
            } else AssistantMarkdown(text)
        }
    }
}

@Composable
private fun AssistantMarkdown(text: String) {
    val blocks = remember(text) { parseAssistantMarkdown(text) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                is ChatMarkdownBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
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
private fun ChangeSummary(state: ChatUiState, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    var details by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { details = !details }, enabled = state.lastTurnFiles.isNotEmpty()) {
                Text(if (state.lastTurnFileCount == 0) "Project changed"
                    else "${state.lastTurnFileCount} file${if (state.lastTurnFileCount == 1) "" else "s"} changed")
            }
            TextButton(onClick = onUndo, enabled = !state.streaming && !state.undoing &&
                state.undoCheckpointId != null) { Text(if (state.undoing) "Undoing…" else "Undo") }
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
private fun ErrorRow(error: String, onDismiss: () -> Unit, actionLabel: String?, onAction: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium)
            Row {
                if (onAction != null && actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_error_dismiss)) }
            }
        }
    }
}

@Composable
private fun InputBar(streaming: Boolean, ready: Boolean, acceptedMessageCount: Long,
                     error: String?, notice: String?, onSend: (String) -> Unit,
                     onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf("") }
    var pendingText by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedAt by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(acceptedMessageCount, streaming, error, notice) {
        if (pendingText != null && acceptedMessageCount > submittedAt) {
            if (draft == pendingText) draft = ""
            pendingText = null
        } else if (!streaming && (error != null || notice != null)) {
            pendingText = null
        }
    }
    val canSend = ready && draft.isNotBlank() && pendingText == null
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Rivet…") },
                minLines = 1,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            IconButton(onClick = {
                if (streaming) onCancel() else {
                    pendingText = draft
                    submittedAt = acceptedMessageCount
                    onSend(draft)
                }
            }, enabled = streaming || canSend, modifier = Modifier.size(48.dp)
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
