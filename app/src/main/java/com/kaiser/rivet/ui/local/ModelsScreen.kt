package com.kaiser.rivet.ui.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.local.DownloadJob
import com.kaiser.rivet.local.DownloadStatus
import com.kaiser.rivet.local.FitBadge
import com.kaiser.rivet.local.InstalledLocalModel
import com.kaiser.rivet.local.LocalModelEntry
import com.kaiser.rivet.local.ModelFit
import com.kaiser.rivet.local.ModelsViewModel
import com.kaiser.rivet.ui.RivetOutlinedButton

@Composable
fun ModelsScreen(viewModel: ModelsViewModel, firstRun: Boolean, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val installed by viewModel.installed.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val installedById = remember(installed) { installed.associateBy { it.entry.id } }
    LaunchedEffect(Unit) { viewModel.refresh() }
    var byoOpen by remember { mutableStateOf(false) }
    var byoUrl by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<LocalModelEntry?>(null) }
    var mobileTarget by remember { mutableStateOf<LocalModelEntry?>(null) }
    val requestDownload: (LocalModelEntry) -> Unit = { entry ->
        if (viewModel.mobileWarning(entry) != null) mobileTarget = entry
        else viewModel.download(entry)
    }
    var exportTarget by remember { mutableStateOf<LocalModelEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) viewModel.export(target, uri)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.staleBytes > 0) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatBytes(ui.staleBytes)} of old partial downloads",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::cleanupStale) { Text("Clean up") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (firstRun) "Start offline" else "Models",
                        style = MaterialTheme.typography.headlineSmall)
                    Text("Runs on this phone. No account, no cloud bill.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
        item {
            DeviceCard(ui.totalRamMb, ui.availableRamMb, ui.freeStorage)
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only for large downloads", style = MaterialTheme.typography.bodyMedium)
                    Text("Keeps a 2.5 GB download off mobile data unless you choose otherwise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
            }
        }
        ui.catalogueError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        ui.notice?.let { notice ->
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::dismissNotice) { Text("Dismiss") }
                    }
                }
            }
        }
        if (ui.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } else if (ui.entries.isNotEmpty()) {
            item {
                val primary = ui.recommendation.primary
                if (firstRun) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("For your phone", style = MaterialTheme.typography.titleMedium)
                        primary?.let {
                            ModelCard(
                                entry = it,
                                fit = ui.fitFor(it),
                                recommended = true,
                                installed = installedById[it.id],
                                job = jobs[it.id],
                                firstRun = true,
                                onDownload = { requestDownload(it) },
                                onUse = { viewModel.activate(it); onClose() },
                                onDelete = { deleteTarget = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ui.recommendation.why.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } ?: Text("This phone has less than 4 GB of RAM. Local models need at least 4 GB.")
                        ui.recommendation.alternative?.let { alt ->
                            AlternativeRow(
                                entry = alt,
                                fit = ui.fitFor(alt),
                                installed = installedById[alt.id],
                                job = jobs[alt.id],
                                onDownload = { requestDownload(alt) },
                                onUse = { viewModel.activate(alt); onClose() },
                            )
                        }
                    }
                } else {
                    Text("For your phone", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top) {
                        val options = ui.recommendation.options
                        options.forEachIndexed { index, entry ->
                            ModelCard(
                                entry = entry,
                                fit = ui.fitFor(entry),
                                recommended = index == 0,
                                installed = installedById[entry.id],
                                job = jobs[entry.id],
                                firstRun = false,
                                onDownload = { requestDownload(entry) },
                                onUse = { viewModel.activate(entry); onClose() },
                                onDelete = { deleteTarget = entry },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    ui.recommendation.why.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val activeJobs = jobs.values.filter { installedById[it.entry.id] == null }
        if (activeJobs.isNotEmpty()) {
            item { Text("Downloads", style = MaterialTheme.typography.titleMedium) }
            items(activeJobs, key = { "job-${it.entry.id}" }) { job ->
                DownloadCard(job = job,
                    fit = ui.fitFor(job.entry),
                    warning = viewModel.storageWarning(job),
                    network = viewModel.networkProblem(job),
                    onPause = { viewModel.pause(job) },
                    onResume = { viewModel.resume(job) },
                    onMobile = { viewModel.resume(job, allowMobile = true) },
                    onCancel = { viewModel.cancel(job) })
            }
        }
        val customInstalled = installed.filter { it.byo && ui.entries.none { entry -> entry.id == it.entry.id } }
        if (customInstalled.isNotEmpty()) {
            item { Text("Your models", style = MaterialTheme.typography.titleMedium) }
            items(customInstalled, key = { "installed-${it.entry.id}" }) { model ->
                InstalledRow(model = model,
                    fit = ui.fitFor(model.entry),
                    onUse = { viewModel.activate(model.entry); onClose() },
                    onExport = { exportTarget = model.entry; exportLauncher.launch("${model.entry.name}.gguf") },
                    onDelete = { deleteTarget = model.entry })
            }
        }
        if (ui.entries.isNotEmpty()) {
            item {
                Text("All models", style = MaterialTheme.typography.titleMedium)
            }
            items(ui.entries, key = { it.id }) { entry ->
                ModelRow(
                    entry = entry,
                    fit = ui.fitFor(entry),
                    installed = installedById[entry.id],
                    job = jobs[entry.id],
                    onClick = {
                        if (installedById[entry.id] != null) viewModel.activate(entry)
                        else if (ui.fitFor(entry).canDownload) requestDownload(entry)
                    },
                    onDelete = { deleteTarget = entry },
                    onExport = { exportTarget = entry; exportLauncher.launch("${entry.name}.gguf") },
                )
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Bring your own GGUF", style = MaterialTheme.typography.titleMedium)
            Text("Paste a Hugging Face repo or file link. Rivet checks for a GGUF before downloading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            RivetOutlinedButton(onClick = { byoOpen = true },
                modifier = Modifier.fillMaxWidth()) { Text("Add a model link") }
        }
    }

    if (byoOpen) {
        AlertDialog(
            onDismissRequest = { if (!ui.byoBusy) byoOpen = false },
            title = { Text("Add a GGUF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hugging Face repo or file link", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = byoUrl,
                        onValueChange = { byoUrl = it; viewModel.dismissByoError() },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://huggingface.co/…") },
                        singleLine = true,
                        enabled = !ui.byoBusy,
                    )
                    ui.byoError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    if (ui.byoBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.addByo(byoUrl) }, enabled = !ui.byoBusy) {
                    Text(if (ui.byoBusy) "Checking…" else "Add")
                }
            },
            dismissButton = { TextButton(onClick = { byoOpen = false }, enabled = !ui.byoBusy) { Text("Cancel") } },
        )
    }

    mobileTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { mobileTarget = null },
            title = { Text("Download on this network?") },
            text = { Text(viewModel.mobileWarning(target) ?: "This download can use a lot of data.") },
            confirmButton = {
                TextButton(onClick = {
                    mobileTarget = null
                    viewModel.download(target, allowMobile = true)
                }) { Text("Use mobile data") }
            },
            dismissButton = {
                TextButton(onClick = {
                    mobileTarget = null
                    viewModel.download(target)
                }) { Text("Wait for Wi-Fi") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("The downloaded file is removed from this phone. Your chats stay.") },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(target); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun DeviceCard(totalRamMb: Int, availableRamMb: Int, freeStorage: Long) {
    val ceiling = com.kaiser.rivet.local.ModelRecommender.ceilingFor(totalRamMb)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("This phone", style = MaterialTheme.typography.labelLarge)
            Text(
                if (totalRamMb > 0) {
                    "${formatRam(totalRamMb)} RAM · ${formatRam(availableRamMb)} free now · ${formatBytes(freeStorage)} storage"
                } else "Reading memory…",
                style = MaterialTheme.typography.bodySmall)
            if (totalRamMb > 0) {
                Text("Safe model ceiling: about ${formatParams(ceiling.maxParamsB)} at ${ceiling.contextMax / 1024}K context.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModelCard(
    entry: LocalModelEntry,
    fit: ModelFit,
    recommended: Boolean,
    installed: InstalledLocalModel?,
    job: DownloadJob?,
    firstRun: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (recommended) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(if (firstRun) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (recommended) {
                Text(if (firstRun) "Recommended for your phone" else "Recommended for your phone",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(entry.name, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${entry.params} · ${formatBytes(entry.size)} · ${entry.contextMax / 1024}K context",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (firstRun) Text(entry.blurb, style = MaterialTheme.typography.bodySmall)
            FitLabel(fit)
            fit.reason.takeIf { it.isNotBlank() && fit.badge != FitBadge.BestFit }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                installed != null -> {
                    Text(downloadVerificationText(installed.checksumVerified),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RivetOutlinedButton(onClick = onUse) { Text("Use") }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                }
                job != null -> Text(job.status.label() + job.error?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall)
                !fit.canDownload -> RivetOutlinedButton(onClick = {}, enabled = false) {
                    Text("Too big for this phone")
                }
                else -> RivetOutlinedButton(onClick = onDownload) { Text("Download") }
            }
        }
    }
}

@Composable
private fun AlternativeRow(
    entry: LocalModelEntry,
    fit: ModelFit,
    installed: InstalledLocalModel?,
    job: DownloadJob?,
    onDownload: () -> Unit,
    onUse: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lighter option", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                Text("${entry.params} · ${formatBytes(entry.size)} · ${fit.badge.label()}",
                    style = MaterialTheme.typography.bodySmall)
            }
            when {
                installed != null -> TextButton(onClick = onUse) { Text("Use") }
                job != null -> Text(job.status.label(), style = MaterialTheme.typography.bodySmall)
                fit.canDownload -> TextButton(onClick = onDownload) { Text("Download") }
                else -> Text("Too big", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModelRow(
    entry: LocalModelEntry,
    fit: ModelFit,
    installed: InstalledLocalModel?,
    job: DownloadJob?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${entry.params} · ${formatBytes(entry.size)} · ${entry.contextMax / 1024}K context",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FitLabel(fit)
            }
            Text(entry.blurb, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            fit.reason.takeIf { it.isNotBlank() && fit.badge != FitBadge.BestFit }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    installed != null -> {
                        Text("Installed", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(downloadVerificationText(installed.checksumVerified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onClick) { Text("Use") }
                        TextButton(onClick = onExport) { Text("Export") }
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                    job != null -> Text(job.status.label(), style = MaterialTheme.typography.bodySmall)
                    fit.canDownload -> TextButton(onClick = onClick) { Text("Download") }
                    else -> Text("Not available on this phone", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun InstalledRow(
    model: InstalledLocalModel,
    fit: ModelFit,
    onUse: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(downloadVerificationText(model.checksumVerified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FitLabel(fit)
            }
            TextButton(onClick = onUse) { Text("Use") }
            TextButton(onClick = onExport) { Text("Export") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun DownloadCard(
    job: DownloadJob,
    fit: ModelFit,
    warning: String?,
    network: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMobile: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.entry.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("${(job.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(progress = { job.fraction }, modifier = Modifier.fillMaxWidth())
            Text("${formatBytes(job.downloaded)} of ${formatBytes(job.total)} · ${job.status.label()}",
                style = MaterialTheme.typography.bodySmall)
            FitLabel(fit)
            job.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            warning?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            network?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.status == DownloadStatus.Downloading || job.status == DownloadStatus.Queued ||
                    job.status == DownloadStatus.Verifying) {
                    TextButton(onClick = onPause) { Text("Pause") }
                } else {
                    TextButton(onClick = onResume) { Text("Resume") }
                    networkAllowLabel(network)?.let { label ->
                        TextButton(onClick = onMobile) { Text(label) }
                    }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (job.entry.sha256 == null && job.status != DownloadStatus.Downloading) {
                Text("No published checksum for this file. Rivet will still check its size.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FitLabel(fit: ModelFit) {
    val color = when (fit.badge) {
        FitBadge.BestFit -> MaterialTheme.colorScheme.primary
        FitBadge.RunsFine -> MaterialTheme.colorScheme.secondary
        FitBadge.Heavy -> MaterialTheme.colorScheme.tertiary
        FitBadge.TooBig -> MaterialTheme.colorScheme.error
    }
    Text(fit.badge.label(), style = MaterialTheme.typography.labelMedium, color = color)
}

internal fun downloadVerificationText(verified: Boolean): String =
    if (verified) "Downloaded and checksum verified"
    else "Downloaded; this repo did not publish a checksum"

/** The one action that can clear a metered-network wait, named for that network. */
internal fun networkAllowLabel(network: String?): String? = when {
    network == null -> null
    network.contains("mobile data", ignoreCase = true) -> "Use mobile data"
    network.contains("Wi-Fi is set as metered", ignoreCase = true) -> "Use this Wi-Fi"
    network.contains("metered", ignoreCase = true) -> "Use this network"
    else -> null
}

private fun FitBadge.label(): String = when (this) {
    FitBadge.BestFit -> "Best fit"
    FitBadge.RunsFine -> "Runs fine"
    FitBadge.Heavy -> "Heavy — may close other apps"
    FitBadge.TooBig -> "Too big for this phone"
}

private fun DownloadStatus.label(): String = when (this) {
    DownloadStatus.Queued -> "Waiting"
    DownloadStatus.Downloading -> "Downloading"
    DownloadStatus.Paused -> "Paused"
    DownloadStatus.Verifying -> "Checking"
    DownloadStatus.Failed -> "Stopped"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    else -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)
}

private fun formatRam(mb: Int): String = String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)

private fun formatParams(params: Double): String =
    if (params % 1.0 == 0.0) "${params.toInt()}B" else "${params}B"
