package com.kaiser.rivet.ui.workspace

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.starter.StarterOption
import com.kaiser.rivet.ui.RivetPageHeader
import com.kaiser.rivet.ui.starter.rememberStarterListing
import com.kaiser.rivet.ui.starter.starterRows
import com.kaiser.rivet.ui.workspaceFolderPath
import kotlinx.coroutines.flow.first

@Composable
internal fun ProjectsScreen(viewModel: ChatViewModel, providerIds: List<String>, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val enabled = state.ready && !state.streaming && !state.projectLoading &&
        !state.undoing && !state.recoveringProjectChanges
    var pickingStarter by rememberSaveable { mutableStateOf(false) }
    var pendingProject by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFlags by rememberSaveable { mutableIntStateOf(0) }
    var opening by rememberSaveable { mutableStateOf(false) }

    fun openFolder(uri: Uri, flags: Int) {
        opening = true
        viewModel.selectProject(uri, flags)
    }
    fun acceptFolder(uri: Uri, flags: Int) {
        if (state.currentSessionWorkspaceId != uri.toString() && state.messages.isNotEmpty()) {
            pendingProject = uri.toString()
            pendingFlags = flags
        } else {
            openFolder(uri, flags)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val starterPick = pickingStarter
        pickingStarter = false
        val starter = viewModel.takePendingStarter()
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val flags = result.data?.flags ?: 0
            if (starterPick) {
                if (starter != null) {
                    opening = true
                    viewModel.installStarter(uri, flags, starter)
                } else viewModel.starterPickerInterrupted()
            } else {
                acceptFolder(uri, flags)
            }
        }
    }
    fun chooseProject(starter: StarterOption? = null, initial: Uri? = null) {
        if (!enabled) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (initial != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        viewModel.setPendingStarter(starter)
        pickingStarter = starter != null
        try {
            picker.launch(intent)
        } catch (failure: RuntimeException) {
            if (failure !is ActivityNotFoundException && failure !is SecurityException) throw failure
            viewModel.takePendingStarter()
            pickingStarter = false
            if (starter == null) viewModel.projectPickerUnavailable() else viewModel.starterPickerInterrupted()
        }
    }
    fun openRecent(identity: String) {
        val uri = Uri.parse(identity)
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (permission?.isReadPermission == true) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            acceptFolder(uri, flags)
        } else {
            chooseProject(initial = uri)
        }
    }

    LaunchedEffect(opening) {
        if (opening) {
            val result = viewModel.uiState.first { !it.projectLoading }
            opening = false
            if (result.projectError == null && result.starterError == null) onBack()
        }
    }
    BackHandler(enabled = pendingProject != null) { pendingProject = null }
    val starterListing = rememberStarterListing(providerIds)
    val recent = state.sessions.sortedByDescending { it.updatedAt }
        .mapNotNull { it.workspaceId }.distinct().filterNot { it == state.projectIdentity }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            RivetPageHeader("Projects", onBack = {
                if (pendingProject != null) pendingProject = null else onBack()
            })
            val pending = pendingProject
            if (pending != null) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start a new conversation?", style = MaterialTheme.typography.titleLarge)
                    Text("This conversation is linked to another project. Start a new conversation for the folder you chose?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { pendingProject = null }) { Text("Cancel") }
                        TextButton(enabled = enabled, onClick = {
                            pendingProject = null
                            openFolder(Uri.parse(pending), pendingFlags)
                        }) { Text("Start new") }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                    item {
                        FolderHeading("Working directory")
                        Text(state.projectName ?: "No folder selected", style = MaterialTheme.typography.titleLarge)
                        workspaceFolderPath(state.projectIdentity)?.let {
                            Text(it, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.projectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.projectLoading) Text("Opening folder…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FolderRow("Open another folder", R.drawable.ic_add, enabled, onClick = { chooseProject() })
                    }
                    if (recent.isNotEmpty()) {
                        item { FolderHeading("Recent folders") }
                        items(recent, key = { "folder:$it" }) { identity ->
                            val path = workspaceFolderPath(identity) ?: identity
                            FolderRow(path.trimEnd('/').substringAfterLast('/'), R.drawable.ic_files, enabled,
                                detail = path, onClick = { openRecent(identity) })
                        }
                    }
                    item {
                        FolderHeading("Starters")
                        Text("Choose a starter and an empty folder for its files.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                        state.starterError?.let {
                            Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    starterRows(starterListing, enabled) { chooseProject(starter = it) }
                }
            }
        }
    }
}

@Composable
private fun FolderHeading(title: String) {
    Text(title, Modifier.padding(top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FolderRow(title: String, iconRes: Int, enabled: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(iconRes), null, Modifier.size(21.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
