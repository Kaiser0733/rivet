package com.kaiser.rivet.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
    val state by viewModel.uiState.collectAsState()
    val actionsEnabled = state.ready && !state.projectLoading && !state.streaming &&
        !state.undoing && !state.recoveringProjectChanges
    var renameTarget by remember { mutableStateOf<CodingSessionHeader?>(null) }
    var deleteTarget by remember { mutableStateOf<CodingSessionHeader?>(null) }
    var title by rememberSaveable { mutableStateOf("") }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("Name") }, singleLine = true)
            },
            confirmButton = { RivetOutlinedButton(onClick = {
                viewModel.renameSession(target.id, title)
                renameTarget = null
            }, enabled = title.isNotBlank() && actionsEnabled) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = { Text("This removes it from Rivet. Project files are not changed.") },
            confirmButton = { RivetOutlinedButton(onClick = {
                viewModel.deleteSession(target.id)
                deleteTarget = null
            }, enabled = actionsEnabled) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("History", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.newSession(); onBack() }, enabled = actionsEnabled) {
                Icon(painterResource(R.drawable.ic_add), "New conversation",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f))

        if (state.sessions.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                RivetDoodleMark(RivetDoodle.Ready)
                Text("Your conversations will appear here.",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp))
                Text("Start a new conversation whenever you want to work on something else.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                RivetOutlinedButton(onClick = { viewModel.newSession(); onBack() }, enabled = actionsEnabled) {
                    Text("New conversation")
                }
            }
        } else {
            LazyColumn(Modifier.widthIn(max = 820.dp).fillMaxSize().align(Alignment.CenterHorizontally),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 12.dp, top = 4.dp, bottom = 16.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    HistoryRow(
                        session = session,
                        current = session.id == state.currentSessionId,
                        enabled = actionsEnabled,
                        onOpen = { viewModel.resumeSession(session.id); onBack() },
                        onPin = { viewModel.setSessionPinned(session.id, !session.pinned) },
                        onRename = {
                            title = session.title
                            renameTarget = session
                        },
                        onDelete = { deleteTarget = session },
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
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)
        .padding(start = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(session.title.replace("New session", "New conversation"),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val context = listOfNotNull(
                relativeRecency(session.updatedAt),
                if (current) "Current" else null,
            ).joinToString(" · ")
            if (context.isNotEmpty()) Text(context, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPin, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_pin), if (session.pinned) "Unpin conversation" else "Pin conversation",
                tint = if (session.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
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
}

internal fun relativeRecency(updatedAt: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(updatedAt, now, DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()
