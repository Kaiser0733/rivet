package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.subagent.WorkerRecord

internal fun workerStateLabel(worker: WorkerRecord): String = when (worker.state) {
    RunPhase.Queued -> "waiting"
    RunPhase.Running -> "running"
    RunPhase.Paused -> "paused"
    RunPhase.Done -> "done"
    RunPhase.Failed -> "failed"
    RunPhase.Stopped -> "stopped"
}

internal fun workerActivityLine(worker: WorkerRecord): String? {
    val activity = worker.activity?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        activity != null -> activity
        worker.waitingReason?.isNotBlank() == true -> worker.waitingReason
        worker.pauseReason != null -> worker.pauseReason.plain
        worker.state == RunPhase.Queued -> "waiting for a slot"
        else -> null
    }
}

/**
 * The header chip is deliberately quiet: a wrap-content button at least 48dp tall
 * while workers exist. It overlays the title row (see chatTitleEndPaddingDp), so the
 * icon row never reflows when a worker starts or finishes.
 */
@Composable
internal fun SubagentChip(
    workers: List<WorkerRecord>,
    onWorkerClick: (WorkerRecord) -> Unit,
    onChipClick: (WorkerRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (workers.isEmpty()) {
        Spacer(Modifier.size(48.dp))
        return
    }
    var open by remember { mutableStateOf(false) }
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { chipSheetWorker(workers)?.let(onChipClick) },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 108.dp),
        ) {
            Text(subagentChipText(workers.size) ?: "",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Box {
            TextButton(
                onClick = { open = true },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 32.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text("⌄", style = MaterialTheme.typography.labelLarge)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                workers.forEach { worker ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(worker.job, style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${worker.modelLabel} · ${workerStateLabel(worker)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                workerActivityLine(worker)?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        onClick = {
                            open = false
                            onWorkerClick(worker)
                        },
                    )
                }
            }
        }
    }
}
