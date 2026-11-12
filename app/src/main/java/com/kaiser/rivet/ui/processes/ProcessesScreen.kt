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
import androidx.compose.material3.AlertDialog
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
import com.kaiser.rivet.runtime.ManagedProcessKind
import com.kaiser.rivet.runtime.ManagedProcessStatus
import com.kaiser.rivet.runtime.InteractiveTerminalSession
import com.kaiser.rivet.runtime.ManagedProcesses
import com.kaiser.rivet.runtime.RunCheckpointInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun ProcessesScreen(processes: ManagedProcesses, onBack: () -> Unit) {
    ProcessesContent(processes, Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), onBack)
}

/**
 * Panel form: the right slide's tools surface. The New terminal card comes first, in the
 * same card chrome as the process rows, then the live process list, so a notification's
 * deep link still lands on processes and the terminal is one tap away.
 */
@Composable
internal fun ToolsPane(
    processes: ManagedProcesses,
    terminal: InteractiveTerminalSession,
    stateLine: String,
    onOpenTerminal: () -> Unit = {},
    checkpoints: List<RunCheckpointInfo> = emptyList(),
    onRestoreCheckpoint: (String) -> Unit = {},
    checkpointNotice: String? = null,
    modifier: Modifier = Modifier,
) {
    ProcessesContent(processes, modifier, null, stateLine = stateLine,
        onOpenTerminal = {
            terminal.open()
            onOpenTerminal()
        }, checkpoints = checkpoints, onRestoreCheckpoint = onRestoreCheckpoint,
        checkpointNotice = checkpointNotice)
}

private data class RestoreTarget(val checkpoint: RunCheckpointInfo, val undoRun: Boolean)

/** The dialog action name follows the intent: restore a checkpoint, or undo one run. */
internal fun restorePrimaryLabel(undoRun: Boolean): String =
    if (undoRun) "Undo this run" else "Restore"

@Composable
private fun ProcessesContent(processes: ManagedProcesses, modifier: Modifier, onBack: (() -> Unit)?,
                             stateLine: String? = null,
                             onOpenTerminal: (() -> Unit)? = null,
                             checkpoints: List<RunCheckpointInfo> = emptyList(),
                             onRestoreCheckpoint: (String) -> Unit = {},
                             checkpointNotice: String? = null) {
    val context = LocalContext.current
    var restoreTarget by remember { mutableStateOf<RestoreTarget?>(null) }
    val processItems by processes.processes.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val activeIds = processItems.filter { it.status in ACTIVE }.map { it.id }
    LaunchedEffect(activeIds) {
        while (activeIds.isNotEmpty()) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    if (onBack != null) BackHandler(onBack = onBack)

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_back), "Back to chat",
                    tint = MaterialTheme.colorScheme.onBackground)
            } else Spacer(Modifier.size(16.dp))
            Column {
                Text(if (onOpenTerminal == null) "Processes" else "Tools",
                    style = MaterialTheme.typography.titleLarge)
                if (stateLine != null) {
                    Text(stateLine, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
        if (onOpenTerminal != null) {
            Surface(
                onClick = onOpenTerminal,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp,
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("New terminal", style = MaterialTheme.typography.titleMedium)
                    Text("Run commands in the Session workspace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
                        TextButton(onClick = { restoreTarget = RestoreTarget(it, undoRun = true) }) {
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
                                    (if (checkpoint.gitRef != null) "hidden git ref · " else "delta copy · ") +
                                    checkpoint.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = {
                            restoreTarget = RestoreTarget(checkpoint, undoRun = false)
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
        if (processItems.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing is running right now.", style = MaterialTheme.typography.titleMedium)
                Text("Rivet will show commands and local previews here while they are active.",
                    Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().align(Alignment.CenterHorizontally),
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
                onRestoreCheckpoint(checkpoint.id)
            }) { Text(restorePrimaryLabel(target.undoRun)) } },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        )
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
            val elapsed = (process.finishedAtMillis ?: now) - process.startedAtMillis
            Text(if (active) "Elapsed ${duration(elapsed)}"
                else "Ran for ${duration(elapsed)}" +
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
                if (process.url != null && process.status == ManagedProcessStatus.Running) {
                    TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = onCopy) { Text("Copy link") }
                }
                Spacer(Modifier.weight(1f))
                if (process.status == ManagedProcessStatus.Starting || process.status == ManagedProcessStatus.Running) {
                    TextButton(onClick = onStop) { Text("Stop") }
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
