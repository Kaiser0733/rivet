package com.kaiser.rivet.ui.provider

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaiser.rivet.ui.RivetOutlinedButton
import com.kaiser.rivet.updates.UpdateUiState
import com.kaiser.rivet.updates.UpdatesViewModel

@Composable
internal fun UpdatesSection(versionName: String, updates: UpdatesViewModel = viewModel()) {
    val state by updates.state.collectAsState()
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.saveDocument(if (it.resultCode == Activity.RESULT_OK) it.data?.data else null)
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Current version: $versionName", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val current = state) {
            UpdateUiState.Idle -> RivetOutlinedButton(onClick = updates::check) { Text("Check for updates") }
            UpdateUiState.Checking -> {
                Text("Checking for updates…", Modifier.padding(vertical = 8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = updates::cancel) { Text("Cancel") }
            }
            UpdateUiState.Current -> {
                Text("Rivet is up to date.", Modifier.padding(vertical = 8.dp))
                TextButton(onClick = updates::check) { Text("Check again") }
            }
            is UpdateUiState.Available -> {
                Text("Rivet v${current.release.version} is available.", Modifier.padding(vertical = 8.dp))
                RivetOutlinedButton(onClick = updates::download) { Text("Download update") }
            }
            is UpdateUiState.Downloading -> {
                Text("Downloading Rivet v${current.release.version}…", Modifier.padding(vertical = 8.dp))
                val total = current.total
                if (total != null && total > 0) {
                    val fraction = (current.bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${current.bytes / 1024} KB downloaded", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = updates::cancel) { Text("Cancel") }
            }
            is UpdateUiState.Verifying -> Text("Verifying update…", Modifier.padding(vertical = 8.dp))
            is UpdateUiState.Saving -> Text("Saving update…", Modifier.padding(vertical = 8.dp))
            is UpdateUiState.AwaitingSave -> {
                Text("The update is verified. Choose where to save it.", Modifier.padding(vertical = 8.dp))
                RivetOutlinedButton(enabled = !current.pickerOpen, onClick = {
                    updates.beginSavePicker()?.let { intent ->
                        try { savePicker.launch(intent) } catch (_: Exception) { updates.pickerUnavailable() }
                    }
                }) { Text("Save update") }
                TextButton(onClick = updates::cancel) { Text("Cancel") }
            }
            is UpdateUiState.Complete -> {
                Text("Rivet v${current.release.version} downloaded.", Modifier.padding(top = 8.dp))
                Text(current.location, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = updates::openDownloads) { Text("Open Downloads") }
                current.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            is UpdateUiState.Error -> {
                Text(current.message, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp))
                RivetOutlinedButton(onClick = updates::check) { Text("Retry") }
            }
        }
    }
}
