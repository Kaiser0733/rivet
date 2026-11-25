package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.AgentActivityOperation

@Composable
internal fun TranscriptWorkerRows(operations: List<AgentActivityOperation>) {
    if (LocalOpenTranscriptWorker.current == null) return
    operations.filter { it.workerId != null }.groupBy { it.workerId }.values.forEach { actions ->
        val operation = actions.firstOrNull { it.detail.isNotBlank() } ?: actions.first()
        ActivityOperationRow(operation)
    }
}

@Composable
internal fun TranscriptWorkerDetails(operation: AgentActivityOperation, modifier: Modifier) {
    val job = operation.detail.substringBefore('\n').ifBlank { operation.title }
    val model = operation.detail.substringAfter('\n', "")
    Column(
        modifier = modifier.padding(start = 22.dp, top = 3.dp, bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(displaySafeText(job), style = MaterialTheme.typography.bodyMedium)
        if (model.isNotBlank()) {
            Text(displaySafeText(model), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
