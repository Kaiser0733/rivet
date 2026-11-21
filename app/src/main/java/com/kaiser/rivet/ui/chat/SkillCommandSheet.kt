package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R

@Composable
internal fun SlashCommandPanel(rows: List<SlashRow>, onPick: (SlashRow) -> Unit) {
    Surface(Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        LazyColumn(Modifier.heightIn(max = 264.dp).padding(vertical = 5.dp)) {
            rows.groupBy { it.group }.forEach { (name, section) ->
                if (name != null) item(key = "heading:$name") {
                    Text(name, Modifier.padding(start = 14.dp, top = 9.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(section, key = { row -> row.action?.let { "action:${it.token}" } ?: "skill:${row.name}" }) { row ->
                    val unavailable = when {
                        row.action == SlashAction.Compact && !row.enabled -> "Wait for the reply to finish"
                        row.skill?.userInvocable == false -> "Agent only"
                        else -> null
                    }
                    val icon = when (row.action) {
                        SlashAction.Goal -> R.drawable.ic_pin
                        SlashAction.Feedback -> R.drawable.ic_edit
                        SlashAction.Compact -> R.drawable.ic_history
                        SlashAction.Yolo, SlashAction.BasicYolo -> R.drawable.ic_processes
                        null -> R.drawable.ic_files
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { if (unavailable != null) stateDescription = unavailable }
                        .clickable(enabled = row.enabled, role = Role.Button, onClick = { onPick(row) })
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(icon), null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.name, Modifier.weight(0.9f), style = MaterialTheme.typography.titleSmall,
                            color = if (row.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(displaySafeText(row.description), Modifier.weight(1.4f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
