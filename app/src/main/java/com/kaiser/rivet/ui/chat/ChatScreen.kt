package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatMessage
import com.kaiser.rivet.chat.ChatRole
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.provider.ProvidersViewModel

private val MAX_COLUMN_WIDTH = 640.dp

@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    providersViewModel: ProvidersViewModel,
    onOpenSettings: () -> Unit,
) {
    val chatState by chatViewModel.uiState.collectAsState()
    val providersState by providersViewModel.listState.collectAsState()
    val hasProvider = providersState.configs.isNotEmpty()

    if (!hasProvider) {
        NoProviderState(onOpenSettings)
        return
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        ModelSelector(providersViewModel)
        MessageList(
            messages = chatState.messages,
            streamText = chatState.streamText,
            streaming = chatState.streaming,
            error = chatState.error,
            onDismissError = chatViewModel::clearError,
            modifier = Modifier.weight(1f),
        )
        InputBar(
            streaming = chatState.streaming,
            onSend = chatViewModel::send,
            onCancel = chatViewModel::cancel,
        )
    }
}

@Composable
private fun NoProviderState(onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_no_provider), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.chat_no_provider_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.chat_open_settings)) }
        }
    }
}

@Composable
private fun ModelSelector(providersViewModel: ProvidersViewModel) {
    val listState by providersViewModel.listState.collectAsState()
    val active = listState.configs.firstOrNull { it.id == listState.activeId }
    var open by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(
                    active?.let { "${it.name} · ${it.model}" } ?: stringResource(R.string.chat_no_provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    painterResource(R.drawable.ic_chevron_down),
                    contentDescription = stringResource(R.string.model_switch),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listState.configs.forEach { config ->
                    DropdownMenuItem(
                        text = { Text("${config.name} · ${config.model}") },
                        onClick = {
                            providersViewModel.setActive(config.id)
                            open = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = chatViewModel::clearChat) {
            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.chat_clear))
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    streamText: String,
    streaming: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Follow the stream only while the user is at (or near) the bottom;
    // scrolling up to read must win over auto-scroll.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(messages.size, streamText) {
        if (atBottom && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1 + if (streaming || error != null) 1 else 0)
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().widthIn(max = MAX_COLUMN_WIDTH),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages) { message ->
                MessageRow(message)
            }
            if (streaming && streamText.isNotEmpty()) {
                item { Text(streamText, style = MaterialTheme.typography.bodyMedium) }
            }
            if (streaming && streamText.isEmpty()) {
                item {
                    Text(
                        "…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (error != null && !streaming) {
                item {
                    ErrorRow(error, onDismissError)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    if (message.role == ChatRole.User) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    message.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    } else {
        Text(
            message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ErrorRow(error: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_error_dismiss))
            }
        }
    }
}

@Composable
private fun InputBar(streaming: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            minLines = 1,
            maxLines = 6,
        )
        IconButton(
            onClick = {
                if (streaming) onCancel()
                else {
                    onSend(draft)
                    draft = ""
                }
            },
            enabled = streaming || draft.isNotBlank(),
        ) {
            if (streaming) {
                Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.chat_stop))
            } else {
                Icon(painterResource(R.drawable.ic_send), stringResource(R.string.chat_send))
            }
        }
    }
}
