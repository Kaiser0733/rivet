package com.kaiser.rivet.ui.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun ChangesScreen(viewModel: ChangesViewModel) {
    val state by viewModel.state.collectAsState()
    var confirmUndo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo last agent turn?") },
            text = { Text("Rivet will restore files from its checkpoint. Undo stops if the workspace has changed since that turn.") },
            confirmButton = { TextButton(onClick = { confirmUndo = false; viewModel.undo() }) { Text("Restore files") } },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Changes", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = viewModel::refresh) { Text("Refresh") }
        }
        val repository = state.repository
        Text(if (repository?.present == true) "Git · ${repository.branch ?: "detached"}"
            else "No Git repository", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.checkpointAt?.let { stamp ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Agent checkpoint · ${DateFormat.getDateTimeInstance().format(Date(stamp))}",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmUndo = true }, enabled = !state.loading) { Text("Undo") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.files.isEmpty() && !state.loading) {
            Text("No changed files to show.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(0.4f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.files, key = { it.path }) { file ->
                TextButton(onClick = { viewModel.select(file.path) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(file.path, modifier = Modifier.weight(1f))
                        Text(file.status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (state.selectedPath != null) {
            Text(state.selectedPath.orEmpty(), style = MaterialTheme.typography.titleSmall)
            Text(state.diff.ifBlank { "No text diff available." },
                modifier = Modifier.fillMaxWidth().weight(0.6f).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
