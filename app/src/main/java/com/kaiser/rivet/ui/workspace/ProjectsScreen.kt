package com.kaiser.rivet.ui.workspace

import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.ui.UtilityLead
import com.kaiser.rivet.ui.UtilityRow
import com.kaiser.rivet.ui.UtilityGlyph
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
import com.kaiser.rivet.ui.RivetPageHeader
import com.kaiser.rivet.ui.workspaceFolderPath
import kotlinx.coroutines.flow.first

@Composable
internal fun ProjectsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val enabled = state.ready && !state.streaming && !state.projectLoading &&
        !state.undoing && !state.recoveringProjectChanges
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
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            acceptFolder(uri, result.data?.flags ?: 0)
        }
    }
    fun chooseProject(initial: Uri? = null) {
        if (!enabled) return
        try {
            picker.launch(projectFolderPickerIntent(initial))
        } catch (failure: RuntimeException) {
            if (failure !is ActivityNotFoundException && failure !is SecurityException) throw failure
            viewModel.projectPickerUnavailable()
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
                        UtilityLead(R.drawable.ic_folder, state.projectName ?: "No folder selected",
                            workspaceFolderPath(state.projectIdentity)) {
                            state.projectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.projectLoading) Text("Opening folder…")
                            RivetOutlinedButton(onClick = { chooseProject() }, enabled = enabled) {
                                Text("Open another folder")
                            }
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item { FolderHeading("Recent folders") }
                        items(recent, key = { "folder:$it" }) { identity ->
                            val path = workspaceFolderPath(identity) ?: identity
                            FolderRow(path.trimEnd('/').substringAfterLast('/'), R.drawable.ic_files, enabled,
                                detail = path, onClick = { openRecent(identity) })
                        }
                    }
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
    UtilityRow(iconRes, title, detail.orEmpty(), enabled = enabled, onClick = onClick)
}

internal fun projectFolderPickerIntent(initial: Uri? = null): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    ).apply {
        if (initial != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
    }
