package com.kaiser.rivet.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.subagent.PoolEntry
import com.kaiser.rivet.subagent.SubagentPool
import com.kaiser.rivet.subagent.SubagentPoolState

internal const val SUBAGENT_FACTS = "Depth 1 · 2 at once · at most 1 on a local model"
internal const val SUBAGENT_WARNING =
    "Subagents spend tokens too, and the agent may pick any model you tick."

private data class SubagentModelGroup(
    val title: String,
    val configs: List<ProviderConfig>,
)

/** Pure grouping used by the page and its tests: local models are one quiet group. */
private fun subagentModelGroups(configs: List<ProviderConfig>): List<SubagentModelGroup> {
    val (local, cloud) = configs.partition { it.type == ProviderType.Local }
    return buildList {
        if (local.isNotEmpty()) add(SubagentModelGroup("On this phone", local))
        cloud.groupBy { it.name.ifBlank { it.type.name } }
            .toSortedMap()
            .forEach { (name, group) -> add(SubagentModelGroup(name, group)) }
    }
}

internal fun subagentSummary(state: SubagentPoolState, configs: List<ProviderConfig>): String {
    val pool = SubagentPool.resolve(state, configs)
    val count = if (state.allowAgentChoice) pool.entries.size else if (pool.fixed != null) 1 else 0
    if (count == 0) return "Off"
    val models = if (count == 1) "1 model" else "$count models"
    return "$models · ${if (state.allowAgentChoice) "agent chooses" else "fixed"}"
}

internal fun subagentModelRows(
    state: SubagentPoolState,
    configs: List<ProviderConfig>,
): List<Pair<String, List<Pair<ProviderConfig, Boolean>>>> {
    val selected = state.entries.toSet()
    return subagentModelGroups(configs).map { group ->
        group.title to group.configs.map { config ->
            config to (PoolEntry(config.id, config.model) in selected)
        }
    }
}

@Composable
internal fun SubagentsSettings(
    state: SubagentPoolState,
    configs: List<ProviderConfig>,
    onAllowAgentChoice: (Boolean) -> Unit,
    onToggleModel: (ProviderConfig) -> Unit,
    onFixedModel: (ProviderConfig) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val pool = SubagentPool.resolve(state, configs)
    val fixedLabel = pool.fixed?.label ?: "Choose a model"
    DetailScroll {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow agents to choose models for subagents",
                    style = MaterialTheme.typography.bodyLarge)
                Text("Off uses one fixed subagent model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.allowAgentChoice, onCheckedChange = onAllowAgentChoice)
        }
        Text(SUBAGENT_WARNING,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (state.allowAgentChoice) {
            if (configs.isEmpty()) {
                Text("No models configured. Add a provider first.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            subagentModelRows(state, configs).forEach { (group, rows) ->
                Text(group, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                rows.forEach { (config, checked) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { onToggleModel(config) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(config.model.ifBlank { "Model" }, style = MaterialTheme.typography.bodyLarge)
                            Text(config.name, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
            if (pool.entries.isEmpty()) {
                Text("No models ticked. Subagents are off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            Text("Subagent model", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Column {
                TextButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(fixedLabel, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(20.dp))
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    configs.forEach { config ->
                        DropdownMenuItem(
                            text = { Text("${config.name} · ${config.model}") },
                            onClick = {
                                pickerOpen = false
                                onFixedModel(config)
                            },
                        )
                    }
                }
            }
            if (pool.fixed == null) {
                Text("No fixed model chosen. Subagents are off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(SUBAGENT_FACTS,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
