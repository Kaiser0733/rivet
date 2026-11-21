package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import com.kaiser.rivet.ui.RivetPageHeader
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AssistChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.subagent.WorkerRecord

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkerScreen(
    worker: WorkerRecord,
    workers: List<WorkerRecord>,
    onSelect: (String) -> Unit,
    messages: List<AgentMessage>,
    loading: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            RivetPageHeader("Subagents", onDismiss)
            Text(worker.job, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            FlowRow(verticalArrangement = Arrangement.Center) {
                Text("${worker.modelLabel} · ", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(workerStateLabel(worker), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                workers.forEach { entry ->
                    AssistChip(
                        onClick = { onSelect(entry.id) },
                        modifier = Modifier.semantics { selected = entry.id == worker.id },
                        label = {
                            Text(if (entry.id == worker.id) entry.job
                                else "${entry.job} · ${workerSheetState(entry)}")
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (loading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            } else {
                val filtered = messages.filter {
                    it.role != AgentRole.Context && it.internalContext == null
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(
                        items = filtered,
                        key = { index, message -> "${worker.id}:$index:${message.hashCode()}" },
                    ) { index, message ->
                        MessageRow(message, messageKey = 31 * index + message.hashCode())
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Read-only transcript", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (worker.isTerminal) {
                    TextButton(onClick = onContinue) { Text("Continue") }
                } else {
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}
