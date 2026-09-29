package com.kaiser.rivet.ui.processes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.runtime.ManagedProcessInfo
import com.kaiser.rivet.runtime.ManagedProcessKind
import com.kaiser.rivet.runtime.ManagedProcessStatus
import com.kaiser.rivet.runtime.ManagedProcesses
import kotlinx.coroutines.delay

@Composable
fun ProcessesScreen(processes: ManagedProcesses, onBack: () -> Unit) {
    val context = LocalContext.current
    val processItems by processes.processes.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val activeIds = processItems.filter { it.status in ACTIVE }.map { it.id }
    LaunchedEffect(activeIds) {
        while (activeIds.isNotEmpty()) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_back), "Back to chat",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Processes", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
        if (processItems.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing is running right now.", style = MaterialTheme.typography.titleMedium)
                Text("Rivet will show commands and local previews here while they are active.",
                    Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(processItems, key = { it.id }) { process ->
                    ProcessRow(process, now,
                        onStop = { processes.stop(process.id) },
                        onOpen = { process.url?.let { openPreview(context, it) } },
                        onCopy = { process.url?.let { copyPreview(context, it) } })
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(
    process: ManagedProcessInfo,
    now: Long,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    val active = process.status in ACTIVE
    Surface(color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp,
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (process.kind == ManagedProcessKind.Preview) "Local preview" else "Project command",
                        style = MaterialTheme.typography.titleMedium)
                    Text(process.projectName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(statusLabel(process.status), style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(process.label, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (process.url != null) {
                Text(process.url, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (active) "Running for ${duration(now - process.startedAtMillis)}"
                else "Ran for ${duration((process.startedAtMillis.let { now - it }).coerceAtLeast(0))}" +
                    (process.exitCode?.let { " · exit $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (process.output.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                    Text(process.output, Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (process.url != null) {
                    TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = onCopy) { Text("Copy link") }
                }
                Spacer(Modifier.weight(1f))
                if (active) TextButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

private fun statusLabel(status: ManagedProcessStatus) = when (status) {
    ManagedProcessStatus.Starting -> "Starting"
    ManagedProcessStatus.Running -> "Running"
    ManagedProcessStatus.Stopping -> "Stopping"
    ManagedProcessStatus.Completed -> "Finished"
    ManagedProcessStatus.Failed -> "Stopped with a problem"
    ManagedProcessStatus.Stopped -> "Stopped"
}

private fun duration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    return if (minutes == 0L) "${seconds}s" else "${minutes}m ${seconds % 60}s"
}

private fun openPreview(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { Toast.makeText(context, "No browser is available to open this preview.", Toast.LENGTH_SHORT).show() }
}

private fun copyPreview(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Rivet preview", url))
    Toast.makeText(context, "Preview link copied.", Toast.LENGTH_SHORT).show()
}

private val ACTIVE = setOf(ManagedProcessStatus.Starting, ManagedProcessStatus.Running,
    ManagedProcessStatus.Stopping)
