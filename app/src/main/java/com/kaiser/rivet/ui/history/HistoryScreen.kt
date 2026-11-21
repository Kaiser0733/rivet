package com.kaiser.rivet.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.workspaceFolderPath
import com.kaiser.rivet.ui.RivetLayout

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RivetSidebar(
    viewModel: ChatViewModel,
    profileInitial: String?,
    drawerOpen: Boolean,
    onNavigate: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val openEnabled = state.ready && !state.projectLoading && !state.undoing && !state.recoveringProjectChanges
    val actionsEnabled = openEnabled && !state.streaming
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var managingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val resetActions = {
        managingId = null
        editTitle = null
        confirmingDelete = false
    }
    val searchFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searching, drawerOpen) {
        if (searching && drawerOpen) {
            searchFocus.requestFocus()
            keyboard?.show()
        } else if (searching) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    val rows = state.sessions.filter { session -> session.title.contains(search.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = RivetLayout.pageGutter)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rivet", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = {
                searching = !searching
                if (!searching) {
                    search = ""
                    focus.clearFocus()
                    keyboard?.hide()
                }
            }) {
                Icon(painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search),
                    if (searching) "Close search" else "Search chats")
            }
        }
        if (searching) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .focusRequester(searchFocus),
                placeholder = { Text("Search chats") }, singleLine = true)
        }
        Surface(onClick = onOpenProjects, modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), null)
                Column(Modifier.weight(1f)) {
                    Text(state.projectName ?: "Choose a working folder",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    workspaceFolderPath(state.projectIdentity)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(painterResource(R.drawable.ic_chevron_right), "Change working folder")
            }
        }
        Spacer(Modifier.size(8.dp))
        SidebarLink("Tools", R.drawable.ic_tools, onOpenTools)
        SidebarLink("Browser", R.drawable.ic_web, onOpenBrowser)
        SidebarLink("Models", R.drawable.ic_models, onOpenModels)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f)) {
            if (rows.isEmpty()) item {
                Text(if (search.isBlank()) "Your chats will appear here." else "No matching chats.",
                    Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(rows, key = { it.id }) { session ->
                val current = session.id == state.currentSessionId
                Row(Modifier.fillMaxWidth()
                    .background(if (current) MaterialTheme.colorScheme.surfaceContainer else
                        MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                    .combinedClickable(
                        enabled = openEnabled,
                        onClick = {
                            resetActions()
                            viewModel.resumeSession(session.id)
                            onNavigate()
                        },
                        onLongClickLabel = "Manage chat",
                        onLongClick = if (actionsEnabled) ({ resetActions(); managingId = session.id }) else null,
                    ).heightIn(min = 50.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) Icon(painterResource(R.drawable.ic_pin_filled), "Pinned", Modifier.size(16.dp))
                    Text(session.title.replace("New session", "New conversation"), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeRecency(session.updatedAt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (managingId == session.id) {
                    when {
                        confirmingDelete -> Column(Modifier.padding(8.dp)) {
                            Text("Delete this chat? Project files stay where they are.")
                            Row {
                                TextButton(onClick = resetActions) { Text("Cancel") }
                                TextButton(enabled = actionsEnabled, onClick = {
                                    viewModel.deleteSession(session.id)
                                    resetActions()
                                }) { Text("Delete chat") }
                            }
                        }
                        editTitle != null -> Column(Modifier.padding(8.dp)) {
                            OutlinedTextField(editTitle.orEmpty(), { editTitle = it },
                                label = { Text("Chat name") }, singleLine = true)
                            Row {
                                TextButton(onClick = resetActions) { Text("Cancel") }
                                TextButton(enabled = actionsEnabled && !editTitle.isNullOrBlank(), onClick = {
                                    viewModel.renameSession(session.id, editTitle.orEmpty())
                                    resetActions()
                                }) { Text("Save") }
                            }
                        }
                        else -> Row {
                            TextButton(onClick = {
                                viewModel.setSessionPinned(session.id, !session.pinned)
                                resetActions()
                            }, enabled = actionsEnabled) { Text(if (session.pinned) "Unpin" else "Pin") }
                            TextButton(onClick = { editTitle = session.title }, enabled = actionsEnabled) { Text("Rename") }
                            TextButton(onClick = { confirmingDelete = true }, enabled = actionsEnabled) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.newSession(); onNavigate() }, enabled = actionsEnabled,
                shape = MaterialTheme.shapes.small) {
                Text("New chat")
            }
            Surface(onClick = onOpenSettings,
                modifier = Modifier.size(RivetLayout.controlSize).semantics { contentDescription = "Settings" },
                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(contentAlignment = Alignment.Center) {
                    if (profileInitial != null) Text(profileInitial, style = MaterialTheme.typography.titleMedium)
                    else Icon(painterResource(R.drawable.ic_profile), null, Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun SidebarLink(label: String, icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(painterResource(icon), null, Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

internal fun relativeRecency(updatedAt: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(updatedAt, now, DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()
