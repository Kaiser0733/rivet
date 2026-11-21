package com.kaiser.rivet.ui.processes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import com.kaiser.rivet.ui.RivetMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.kaiser.rivet.ui.RivetPageHeader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.kaiser.rivet.runtime.ManagedProcessStatus
import com.kaiser.rivet.runtime.InteractiveTerminalSession
import com.kaiser.rivet.runtime.ManagedProcesses
import com.kaiser.rivet.runtime.RunCheckpointInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private data class RestoreTarget(val checkpoint: RunCheckpointInfo, val undoRun: Boolean)

internal fun restorePrimaryLabel(undoRun: Boolean): String =
    if (undoRun) "Undo this run" else "Restore"

@Composable
internal fun ToolsScreen(
    processes: ManagedProcesses,
    terminal: InteractiveTerminalSession,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenWorkers: (() -> Unit)?,
    checkpoints: List<RunCheckpointInfo>,
    onRestoreCheckpoint: (String, Boolean) -> Unit,
    checkpointNotice: String?,
) {
    val context = LocalContext.current
    val terminalState by terminal.state.collectAsState()
    val processItems by processes.processes.collectAsState()
    val (running, finished) = processItems.partition { it.status in ACTIVE }
    var showFinished by rememberSaveable { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<RestoreTarget?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running.isNotEmpty()) {
        while (running.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            RivetPageHeader("Tools", onBack)
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Surface(onClick = { terminal.open(); onOpenTerminal() },
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_processes), null)
                            Text(if (terminalState.running) "Open terminal" else "New terminal",
                                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(painterResource(R.drawable.ic_chevron_right), null)
                        }
                    }
                }
                if (onOpenWorkers != null) item {
                    TextButton(onClick = onOpenWorkers) { Text("Subagents") }
                }
                item {
                    Text("Running", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
                    if (running.isEmpty()) Text("Nothing is running right now.",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(running, key = { it.id }) { process ->
                    ProcessRow(process, now,
                        onStop = { processes.stop(process.id) },
                        onOpen = { process.url?.let { openPreview(context, it) } },
                        onCopy = { process.url?.let { copyPreview(context, it) } })
                }
                item {
                    CheckpointList(checkpoints, checkpointNotice, onRestore = { restoreTarget = it })
                }
                if (finished.isNotEmpty()) item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { showFinished = !showFinished }) {
                        Text(if (showFinished) "Hide finished" else "Finished processes")
                        Icon(painterResource(if (showFinished) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                            null, Modifier.size(18.dp))
                    }
                }
                if (showFinished) items(finished, key = { it.id }) { process ->
                    ProcessRow(process, now, onStop = {}, onOpen = {}, onCopy = {})
                }
            }
        }
    }
    restoreTarget?.let { target ->
        val checkpoint = target.checkpoint
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(if (target.undoRun) "Undo this run?" else "Restore this checkpoint?") },
            text = { Text(if (target.undoRun)
                "This undoes the project changes from the latest run and keeps the conversation history."
            else
                "Rivet restores only the files it changed. If anything changed outside Rivet, it stops and lists those files instead of overwriting them.") },
            confirmButton = { TextButton(onClick = {
                restoreTarget = null
                onRestoreCheckpoint(checkpoint.id, target.undoRun)
            }) { Text(restorePrimaryLabel(target.undoRun)) } },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheckpointList(checkpoints: List<RunCheckpointInfo>, checkpointNotice: String?,
                           onRestore: (RestoreTarget) -> Unit) {
    if (checkpoints.isEmpty() && checkpointNotice != null) {
        Text(checkpointNotice, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (checkpoints.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val latestRunId = checkpoints.firstOrNull()?.runId
            val runStart = checkpoints.filter { it.runId == latestRunId }
                .minByOrNull { it.sequence }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Run checkpoints", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                runStart?.let {
                    TextButton(onClick = { onRestore(RestoreTarget(it, undoRun = true)) }) {
                        Text("Undo this run")
                    }
                }
            }
            checkpoints.take(6).forEach { checkpoint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(checkpoint.summary,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(Date(checkpoint.createdAt)) + " · " +
                                (if (checkpoint.gitRef != null) "Git checkpoint" else "File checkpoint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        onRestore(RestoreTarget(checkpoint, undoRun = false))
                    }) { Text("Restore") }
                }
            }
            checkpointNotice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Not restored")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checkpoints.size > 6) Text("Showing the latest 6 checkpoints",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun commandFirstLine(command: String): String = command.lineSequence().first()

@Composable
private fun ProcessRow(
    process: ManagedProcessInfo,
    now: Long,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    var expanded by rememberSaveable(process.id) { mutableStateOf(false) }
    val active = process.status in ACTIVE
    val command = process.commandForDisplay ?: process.label
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(RivetMotion.PROCESS_MS, easing = RivetMotion.easing))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(commandFirstLine(command), Modifier.weight(1f), style = mono,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                    null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(command, Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()), style = mono)
                    val elapsed = (process.finishedAtMillis ?: now) - process.startedAtMillis
                    val exit = process.exitCode?.let { " (exit $it)" }.orEmpty()
                    Text("${statusLabel(process.status)} $exit\n${process.projectName} · ${duration(elapsed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    process.url?.let { Text(it, style = mono) }
                    if (process.output.isNotBlank()) {
                        Text(process.output, Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp), style = mono)
                    }
                    if (active) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (process.url != null && process.status == ManagedProcessStatus.Running) {
                            TextButton(onClick = onOpen) { Text("Open") }
                            TextButton(onClick = onCopy) { Text("Copy link") }
                        }
                        if (process.status != ManagedProcessStatus.Stopping) {
                            TextButton(onClick = onStop) { Text("Stop") }
                        }
                    }
                }
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
