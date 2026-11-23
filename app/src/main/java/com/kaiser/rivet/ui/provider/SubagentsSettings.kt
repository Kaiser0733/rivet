package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.ui.UtilityColumns
import com.kaiser.rivet.ui.DisclosureBody
import com.kaiser.rivet.ui.UtilityFold
import com.kaiser.rivet.ui.UtilityLead
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.selectListedModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.kaiser.rivet.subagent.SubagentPool
import com.kaiser.rivet.subagent.SubagentPoolState

internal const val SUBAGENT_FACTS = "Depth 1 · 2 at once · at most 1 on a local model"
internal const val SUBAGENT_WARNING =
    "Subagents spend tokens too, and the agent may pick any model you tick."

private data class SubagentModelGroup(
    val title: String,
    val configs: List<ProviderConfig>,
)

internal data class WorkerModelCatalog(val models: List<ModelInfo>, val error: String? = null) {
    val labels = models.associate { it.id to it.label }
}

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
    if (count == 0) return "No models selected"
    val models = if (count == 1) "1 model" else "$count models"
    return "$models · ${if (state.allowAgentChoice) "agent chooses" else "fixed"}"
}

internal fun droppedSubagentPicks(
    state: SubagentPoolState,
    configs: List<ProviderConfig>,
): Int {
    val byId = configs.associateBy { it.id }
    return state.entries.count { entry -> SubagentPool.resolveConfig(entry, byId[entry.configId]) == null }
}

internal fun droppedSubagentNotice(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 saved pick no longer matches its provider's model"
    else -> "$count saved picks no longer match their provider's model"
}

internal fun subagentModelRows(
    state: SubagentPoolState,
    configs: List<ProviderConfig>,
    catalogs: Map<String, WorkerModelCatalog> = emptyMap(),
): List<Pair<String, List<Pair<ProviderConfig, Boolean>>>> {
    val selected = if (state.allowAgentChoice) state.entries else listOfNotNull(state.fixed)
    return subagentModelGroups(configs).map { group ->
        group.title to group.configs.flatMap { config ->
            val models = catalogs[config.id]?.models.orEmpty().map { config.selectListedModel(it) }
            val saved = selected.filter { it.configId == config.id }
            val choices = (models + listOf(config) + saved.mapNotNull { SubagentPool.resolveConfig(it, config) })
                .distinctBy { it.model }.filter { it.model.isNotBlank() }
            choices.map { choice -> choice to saved.any { it.model == choice.model } }
        }
    }
}

internal fun filterWorkerModelRows(
    rows: List<Pair<ProviderConfig, Boolean>>,
    catalogs: Map<String, WorkerModelCatalog>,
    query: String,
): List<Pair<ProviderConfig, Boolean>> {
    val text = query.trim()
    if (text.isEmpty()) return rows
    return rows.filter { (config, _) ->
        config.model.contains(text, ignoreCase = true) ||
            catalogs[config.id]?.labels?.get(config.model)?.contains(text, ignoreCase = true) == true
    }
}

@Composable
internal fun SubagentsSettings(
    state: SubagentPoolState,
    configs: List<ProviderConfig>,
    error: String? = null,
    catalogs: Map<String, WorkerModelCatalog> = emptyMap(),
    onAllowAgentChoice: (Boolean) -> Unit,
    onToggleModel: (ProviderConfig) -> Unit,
    onFixedModel: (ProviderConfig) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val pool = SubagentPool.resolve(state, configs)
    val groups = remember(state, configs, catalogs) { subagentModelRows(state, configs, catalogs) }
    val groupConfigs = remember(configs) { subagentModelGroups(configs).associate { it.title to it.configs } }
    val fixedLabel = pool.fixed?.label ?: "Choose a model"
    DetailScroll {
        UtilityColumns(
            leading = {
                UtilityLead(R.drawable.ic_subagents, "Worker models", subagentSummary(state, configs))
                Spacer(Modifier.height(12.dp))
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                droppedSubagentNotice(droppedSubagentPicks(state, configs))?.let { notice ->
                    Text(notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
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
                UtilityFold(R.drawable.ic_info, "How subagents work") {
                    Text(SUBAGENT_WARNING, style = MaterialTheme.typography.bodyMedium)
                    Text(SUBAGENT_FACTS, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            },
            trailing = {
                if (state.allowAgentChoice) {
                    if (configs.isEmpty()) {
                        Text("No models configured. Add a provider first.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    WorkerModelGroups(groups, groupConfigs, catalogs, fixed = false, onPick = onToggleModel)
                    if (pool.entries.isEmpty()) {
                        Text("No models selected. Tick a model in one of the provider groups.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    Text("Subagent model", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    TextButton(onClick = { pickerOpen = !pickerOpen }, modifier = Modifier.fillMaxWidth()) {
                        Text(fixedLabel, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(20.dp))
                    }
                    DisclosureBody(pickerOpen) {
                        Column {
                            WorkerModelGroups(groups, groupConfigs, catalogs, fixed = true) {
                                pickerOpen = false
                                onFixedModel(it)
                            }
                        }
                    }
                    if (pool.fixed == null) {
                        Text("No models selected. Choose a fixed model.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
    }
}

@Composable
private fun WorkerModelGroups(
    groups: List<Pair<String, List<Pair<ProviderConfig, Boolean>>>>,
    configsByGroup: Map<String, List<ProviderConfig>>,
    catalogs: Map<String, WorkerModelCatalog>,
    fixed: Boolean,
    onPick: (ProviderConfig) -> Unit,
) {
    for ((group, rows) in groups) key(group) {
        val providers = configsByGroup[group].orEmpty().filter { it.type != ProviderType.Local }
        val status = when {
            providers.any { it.id !in catalogs } -> "Loading models…"
            providers.any { catalogs[it.id]?.error != null } -> "Couldn't load the full model list."
            else -> null
        }
        UtilityFold(R.drawable.ic_models, group, "${rows.count { it.second }} selected",
            contentStartPadding = 8.dp) {
            var query by rememberSaveable { mutableStateOf("") }
            val filtered = remember(rows, catalogs, query) { filterWorkerModelRows(rows, catalogs, query) }
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search models") })
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)) }
                if (filtered.isEmpty() && status == null) {
                    Text("No matching models", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp))
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(filtered, key = { (config, _) -> "${config.id}\u0000${config.model}" }) { (config, checked) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(config) },
                            verticalAlignment = Alignment.CenterVertically) {
                            if (fixed) RadioButton(selected = checked, onClick = null)
                            else Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(config.model, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                Text(config.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
