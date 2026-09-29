package com.kaiser.rivet.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.chat.ChatUiState
import com.kaiser.rivet.chat.ChatErrorAction
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.RivetDoodle
import com.kaiser.rivet.ui.RivetDoodleMark
import com.kaiser.rivet.ui.provider.ProvidersViewModel

private val MAX_COLUMN_WIDTH = 640.dp

@Composable
fun ChatScreen(chatViewModel: ChatViewModel, providersViewModel: ProvidersViewModel,
               onOpenSettings: () -> Unit, onOpenHistory: () -> Unit) {
    val state by chatViewModel.uiState.collectAsState()
    val providers by providersViewModel.listState.collectAsState()
    val wrongProject = state.currentSessionId != null &&
        state.currentSessionWorkspaceId != state.projectIdentity
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
            title = { Text("Start a new conversation?") },
            text = { Text("This conversation is linked to another project. Start a new conversation for the folder you chose?") },
            confirmButton = { TextButton(onClick = {
                pendingProject = null
                chatViewModel.selectProject(selected.first, selected.second)
            }) { Text("Start new") } },
            dismissButton = { TextButton(onClick = { pendingProject = null }) { Text("Cancel") } })
    }
    if (confirmUndo) {
        AlertDialog(onDismissRequest = { confirmUndo = false },
            title = { Text("Undo Rivet's last changes?") },
            text = { Text("Rivet will restore the files it changed. If the project changed afterward, Undo will stop before overwriting newer work.") },
            confirmButton = { TextButton(onClick = {
                confirmUndo = false; chatViewModel.undoLastTurn()
            }) { Text("Undo changes") } },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } })
    }
    state.pendingApproval?.let { approval ->
        ApprovalDialog(approval, chatViewModel::approve, chatViewModel::deny)
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Row(Modifier.widthIn(max = MAX_COLUMN_WIDTH).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenHistory,
                enabled = state.ready && !state.projectLoading && !state.streaming &&
                    !state.undoing && !state.recoveringProjectChanges) {
                Icon(painterResource(R.drawable.ic_history), "Conversation history",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            TextButton(onClick = ::chooseProject, modifier = Modifier.weight(1f),
                enabled = state.ready && !state.streaming && !state.projectLoading && !state.undoing && !state.recoveringProjectChanges) {
                Icon(painterResource(R.drawable.ic_files), null, Modifier.size(18.dp))
                Text(displaySafeText(state.projectName ?: "Choose project"), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp))
            }
        }
        if (providers.configs.isNotEmpty()) {
            ModelSelector(Modifier.widthIn(max = MAX_COLUMN_WIDTH).align(Alignment.CenterHorizontally), providersViewModel)
        }
        HorizontalDivider(Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.23f))
        if (state.projectError != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(state.projectError.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                if (state.projectName != null || state.messages.isNotEmpty() || providers.configs.isEmpty())
                    TextButton(onClick = ::chooseProject) { Text("Choose again") }
            }
        }
        if (wrongProject && state.projectName != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("This conversation belongs to another project.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = ::chooseProject) { Text("Choose its project") }
                    TextButton(onClick = chatViewModel::newSession) { Text("Start new conversation") }
                }
            }
        }
        if (providers.configs.isEmpty()) {
            EmptyState(RivetDoodle.Provider, "Choose a model to get started.",
                "Add a provider in Settings, then tell Rivet what you want to change.",
                "Open Settings", onOpenSettings, Modifier.weight(1f))
        } else if (state.projectName == null && state.messages.isEmpty() && !state.projectLoading) {
            EmptyState(RivetDoodle.Project, "What do you want to work on?",
                "Choose the folder that contains your project, then tell Rivet what you want changed.",
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
            MessageList(state, chatViewModel::clearError, onOpenSettings, chatViewModel::retryProjectChanges,
                Modifier.weight(1f).align(Alignment.CenterHorizontally))
        }
        if (state.undoCheckpointId != null || state.undoing) {
            ChangeSummary(state, onUndo = { confirmUndo = true },
                modifier = Modifier.widthIn(max = MAX_COLUMN_WIDTH).align(Alignment.CenterHorizontally))
        }
        InputBar(streaming = state.streaming, ready = state.ready && state.projectName != null && !wrongProject &&
            providers.configs.isNotEmpty() && !state.projectLoading && !state.undoing && !state.recoveringProjectChanges,
            acceptedMessageCount = state.acceptedMessageCount,
            error = state.error, notice = state.notice,
            onSend = chatViewModel::send, onCancel = chatViewModel::cancel,
            modifier = Modifier.widthIn(max = MAX_COLUMN_WIDTH).align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun EmptyState(kind: RivetDoodle, title: String, detail: String, action: String?,
                       onAction: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp).widthIn(max = 390.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RivetDoodleMark(kind)
            Text(title, style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge)
            if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
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
    val visible = visibleConversation(state.messages)
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
    } }
    LaunchedEffect(visible.size, state.activity, state.error, state.notice) {
        if (atBottom) listState.animateScrollToItem((visible.size +
            (if (state.activity != null) 1 else 0) + (if (state.error != null) 1 else 0) +
            (if (state.notice != null) 1 else 0) - 1).coerceAtLeast(0))
    }
    LazyColumn(state = listState, modifier = modifier.widthIn(max = MAX_COLUMN_WIDTH).fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(visible) { message -> MessageRow(message) }
        state.activity?.let { activity ->
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
private fun MessageRow(message: AgentMessage) {
    if (message.role == AgentRole.User) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Rivet", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun visibleConversation(messages: List<AgentMessage>): List<AgentMessage> =
    messages.filter { it.role == AgentRole.User ||
        (it.role == AgentRole.Assistant && it.text.isNotBlank() && it.toolCalls.isEmpty()) }

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
        title = { Text(displaySafeText(approvalTitle(request))) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(displaySafeText(request.detail))
            if (command) Text(COMMAND_APPROVAL_WARNING)
        } },
        confirmButton = { TextButton(onClick = { onApprove(request.approvalToken) }) {
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
