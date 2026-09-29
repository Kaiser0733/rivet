package com.kaiser.rivet.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.storage.CodingSessionHeader
import com.kaiser.rivet.ui.RivetDoodle
import com.kaiser.rivet.ui.RivetDoodleMark
import com.kaiser.rivet.ui.RivetOutlinedButton

@Composable
fun HistoryScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    HistoryContent(viewModel, Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), onBack)
}

@Composable
fun HistoryPane(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    HistoryContent(viewModel, modifier.background(MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
private fun HistoryContent(viewModel: ChatViewModel, modifier: Modifier, onClose: (() -> Unit)? = null) {
    val embedded = onClose == null
    val state by viewModel.uiState.collectAsState()
    val actionsEnabled = state.ready && !state.projectLoading && !state.streaming &&
        !state.undoing && !state.recoveringProjectChanges
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }

    state.sessions.firstOrNull { it.id == renameId }?.let { target ->
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename conversation", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("Name", fontFamily = FontFamily.Default) }, singleLine = true)
            },
            confirmButton = { RivetOutlinedButton(onClick = {
                viewModel.renameSession(target.id, title)
                renameId = null
            }, enabled = title.isNotBlank() && actionsEnabled) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } },
        )
    }
    state.sessions.firstOrNull { it.id == deleteId }?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete conversation?", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = { Text("This removes it from Rivet. Project files are not changed.",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)) },
            confirmButton = { RivetOutlinedButton(onClick = {
                viewModel.deleteSession(target.id)
                deleteId = null
            }, enabled = actionsEnabled) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } },
        )
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_back), "Back", tint = MaterialTheme.colorScheme.onBackground)
            } else Spacer(Modifier.size(12.dp))
            Text("History", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.newSession(); onClose?.invoke() }, enabled = actionsEnabled) {
                Icon(painterResource(R.drawable.ic_add), "New conversation",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f))

        if (state.sessions.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                RivetDoodleMark(RivetDoodle.Ready)
                Text("Your conversations will appear here.",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp))
                Text("Start a new conversation whenever you want to work on something else.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                RivetOutlinedButton(onClick = { viewModel.newSession(); onClose?.invoke() }, enabled = actionsEnabled) {
                    Text("New conversation")
                }
            }
        } else {
            LazyColumn(Modifier.widthIn(max = 820.dp).fillMaxSize().align(Alignment.CenterHorizontally),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = if (embedded) 12.dp else 20.dp, end = 12.dp, top = 4.dp, bottom = 16.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    HistoryRow(
                        session = session,
                        current = session.id == state.currentSessionId,
                        enabled = actionsEnabled,
                        compact = embedded,
                        onOpen = { viewModel.resumeSession(session.id); onClose?.invoke() },
                        onPin = { viewModel.setSessionPinned(session.id, !session.pinned) },
                        onRename = {
                            title = session.title
                            renameId = session.id
                        },
                        onDelete = { deleteId = session.id },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    session: CodingSessionHeader,
    current: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()
        .then(if (session.pinned) Modifier.background(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
        .clickable(enabled = enabled, onClick = onOpen)
        .padding(start = 4.dp, top = 7.dp, bottom = 7.dp)) {
        if (compact) {
            HistoryTitle(session, current, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                HistoryActions(session.pinned, enabled, onPin, onRename, onDelete)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HistoryTitle(session, current, Modifier.weight(1f))
                HistoryActions(session.pinned, enabled, onPin, onRename, onDelete)
            }
        }
    }
}

@Composable
private fun HistoryTitle(session: CodingSessionHeader, current: Boolean, modifier: Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(session.title.replace("New session", "New conversation"),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(relativeRecency(session.updatedAt), if (current) "Current" else null)
            .joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryActions(pinned: Boolean, enabled: Boolean,
                           onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    IconButton(onClick = onPin, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(if (pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin),
            if (pinned) "Unpin conversation" else "Pin conversation",
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
    }
    IconButton(onClick = onRename, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_edit), "Rename conversation",
            tint = MaterialTheme.colorScheme.onBackground)
    }
    IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_delete), "Delete conversation",
            tint = MaterialTheme.colorScheme.onBackground)
    }
}

internal fun relativeRecency(updatedAt: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(updatedAt, now, DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()
