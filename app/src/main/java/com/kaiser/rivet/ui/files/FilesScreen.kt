package com.kaiser.rivet.ui.files

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.workspace.WorkspaceEntry
import com.kaiser.rivet.workspace.WorkspacePath

@Composable
fun FilesScreen(viewModel: FilesViewModel) {
    val state by viewModel.state.collectAsState()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var action by rememberSaveable { mutableStateOf("") }
    var target by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent -> intent.data?.let { viewModel.select(it, intent.flags) } }
        }
    }
    fun guarded(block: () -> Unit) {
        if (state.mutating) return
        if (state.dirty) pending = block else block()
    }
    fun choose() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        picker.launch(intent)
    }
    fun back() = guarded {
        viewModel.discard()
        viewModel.navigate(if (state.opened != null) state.directory else state.directory.parent())
    }
    BackHandler(state.opened != null || !state.directory.isRoot || state.mutating || state.dirty) { back() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (state.selected) {
                TextButton(onClick = { guarded { viewModel.discard(); viewModel.navigate(WorkspacePath.ROOT) } }, enabled = !state.mutating) { Text("Project") }
                var accumulated = WorkspacePath.ROOT
                state.directory.segments.forEach { segment ->
                    accumulated = accumulated.child(segment)
                    val path = accumulated
                    TextButton(onClick = { guarded { viewModel.discard(); viewModel.navigate(path) } }, enabled = !state.mutating) { Text("/ $segment") }
                }
            }
            TextButton(onClick = { guarded { choose() } }, enabled = !state.mutating && !state.loading) {
                Text(if (state.selected) "Change workspace" else "Select project folder")
            }
        }
        if (!state.selected && !state.loading) {
            Text("Choose a project folder. Rivet only accesses files inside that folder.")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.loading || state.mutating || state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.mutating) Text("Waiting for the document provider. Keep this screen open.", style = MaterialTheme.typography.bodySmall)
        val opened = state.opened
        if (opened != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { back() }, enabled = !state.mutating) { Text("Back to files") }
                TextButton(onClick = viewModel::save, enabled = state.dirty && !state.mutating && !state.loading) { Text("Save") }
                TextButton(onClick = { guarded { viewModel.discard(); viewModel.open(opened.path) } }, enabled = !state.mutating) { Text("Reopen") }
            }
            Text(opened.path.value + if (state.dirty) " • Unsaved" else "", style = MaterialTheme.typography.titleSmall)
            Text(entryMetadata(opened), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.snapshot != null) {
                if (!opened.capabilities.write) Text("Read-only file", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = state.draft, onValueChange = viewModel::edit,
                    modifier = Modifier.fillMaxWidth().weight(1f).imePadding(),
                    readOnly = !opened.capabilities.write || state.mutating,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("UTF-8 text") })
            } else state.fileNotice?.let { Text(it) }
        } else if (state.selected) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { back() }, enabled = !state.directory.isRoot && !state.mutating) { Text("Up") }
                TextButton(onClick = { viewModel.navigate(state.directory) }, enabled = !state.mutating) { Text("Refresh") }
                TextButton(onClick = { action = "New file"; input = "" }, enabled = state.folder?.capabilities?.create == true && !state.mutating && !state.loading) { Text("New file") }
                TextButton(onClick = { action = "New folder"; input = "" }, enabled = state.folder?.capabilities?.create == true && !state.mutating && !state.loading) { Text("New folder") }
            }
            if (state.folder != null && state.folder?.capabilities?.create != true) Text("Folder creation unavailable or read-only", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.query, viewModel::setQuery, Modifier.weight(1f), singleLine = true, label = { Text("Literal project search") })
                TextButton(onClick = if (state.searching) viewModel::cancelSearch else viewModel::search,
                    enabled = state.query.isNotEmpty() && !state.mutating && !state.loading) { Text(if (state.searching) "Cancel" else "Search") }
            }
            val report = state.search
            if (report != null) {
                Text("${report.hits.size} results · ${report.filesScanned} files · ${report.skipped} skipped" +
                    if (report.limited) " · Limit reached" else "", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.setQuery("") }) { Text("Back to directory") }
                LazyColumn(Modifier.weight(1f)) {
                    items(report.hits) { hit ->
                        TextButton(onClick = { viewModel.open(hit.path) }, enabled = !state.mutating) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(hit.path.value + (hit.line?.let { ":$it" } ?: ""))
                                if (hit.line != null) Text(hit.context, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                if (!state.loading && state.entries.isEmpty() && state.error == null) Text("This folder is empty.")
                LazyColumn(Modifier.weight(1f)) {
                    items(state.entries, key = { it.documentId + "|" + it.path.value }) { entry ->
                        FileRow(entry, enabled = !state.mutating && !state.loading, onOpen = { viewModel.open(entry.path) }, onAction = {
                            target = entry; action = it; input = if (it == "Rename") entry.path.name else ""
                        })
                    }
                }
            }
        }
    }
    pending?.let { continueAction ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Unsaved changes") },
            text = { Text("Continue without saving this draft? Canceling the folder picker keeps the current workspace and draft.") },
            confirmButton = { TextButton(onClick = { pending = null; continueAction() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep editing") } })
    }
    if (action.isNotEmpty()) {
        FileActionDialog(action, target, input, { input = it }, { action = "" }, {
            when (action) {
                "New file" -> viewModel.create(input, false)
                "New folder" -> viewModel.create(input, true)
                "Rename" -> target?.let { viewModel.rename(it.path, input) }
                "Move" -> target?.let { viewModel.move(it.path, input) }
                "Delete" -> target?.let { viewModel.delete(it.path) }
            }
            action = ""
        })
    }
}
