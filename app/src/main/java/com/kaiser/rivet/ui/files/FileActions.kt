package com.kaiser.rivet.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.workspace.WorkspaceEntry
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import java.text.DateFormat
import java.util.Date

internal fun entryMetadata(entry: WorkspaceEntry): String = buildList {
    add(if (entry.directory) "Folder" else entry.mimeType)
    entry.size?.let { add("$it bytes") }
    entry.modifiedTime?.let { add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
}.joinToString(" · ")

@Composable
internal fun FileRow(entry: WorkspaceEntry, enabled: Boolean, onOpen: () -> Unit, onAction: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(entry.path.name + if (entry.directory) "/" else "", style = MaterialTheme.typography.bodyLarge)
                Text(entryMetadata(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = enabled) { Text("Actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf("Rename" to entry.capabilities.rename, "Move" to entry.capabilities.move,
                    "Delete" to entry.capabilities.delete).forEach { (label, supported) ->
                    DropdownMenuItem(text = { Text(label) }, enabled = supported,
                        onClick = { menu = false; onAction(label) })
                }
            }
        }
    }
}

@Composable
internal fun FileActionDialog(action: String, target: WorkspaceEntry?, value: String,
    onValue: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val valid = remember(action, value, target) {
        try {
            when (action) {
                "Delete" -> require(target != null)
                "Move" -> { require(target != null); WorkspacePath.parse(value) }
                "Rename" -> { require(target != null); WorkspacePath.validateName(value) }
                else -> WorkspacePath.validateName(value)
            }
            true
        } catch (_: WorkspaceFailure) { false } catch (_: IllegalArgumentException) { false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(action) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            target?.let { Text(it.path.value) }
            if (action == "Delete") Text("Permanently delete this entry${if (target?.directory == true) " and all its contents" else ""}? This cannot be undone.")
            else {
                if (action == "Move") Text("Move into an existing workspace-relative directory. Leave empty for the project root. Only provider-native moves are supported.")
                OutlinedTextField(value, onValue, singleLine = true,
                    label = { Text(if (action == "Move") "Destination folder" else "Name") },
                    isError = value.isNotEmpty() && !valid)
            }
        }
    }, confirmButton = { TextButton(onClick = onConfirm, enabled = valid) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
