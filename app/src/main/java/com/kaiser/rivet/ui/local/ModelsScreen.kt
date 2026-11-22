package com.kaiser.rivet.ui.local

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.kaiser.rivet.ui.UtilityColumns
import com.kaiser.rivet.ui.UtilityFold
import com.kaiser.rivet.R
import com.kaiser.rivet.ui.UtilityGlyph
import com.kaiser.rivet.ui.UtilityLead
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.kaiser.rivet.ui.RivetPageHeader
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
    val modelEntries = uniqueModelEntries(ui.recommendation.options, ui.entries,
        installed.map { it.entry }, jobs.values.map { it.entry })
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

    if (byoOpen) {
        val closeForm = { if (!ui.byoBusy) byoOpen = false }
        BackHandler(onBack = closeForm)
        Column(Modifier.fillMaxSize()) {
            RivetPageHeader("Add a GGUF", closeForm)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                TextButton(onClick = { viewModel.addByo(byoUrl) }, enabled = !ui.byoBusy) {
                    Text(if (ui.byoBusy) "Checking…" else "Add")
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        RivetPageHeader(if (firstRun) "Start offline" else "Models", onClose)
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(320.dp),
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalItemSpacing = 12.dp,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (ui.staleBytes > 0) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${formatBytes(ui.staleBytes)} of old partial downloads",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = viewModel::cleanupStale) { Text("Clean up") }
                    }
                }
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Text("Runs on this phone. No account, no cloud bill.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                UtilityColumns(
                    leading = { DeviceCard(ui.totalRamMb, ui.availableRamMb, ui.freeStorage) },
                    trailing = {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Wi-Fi only for large downloads", style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(checked = ui.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                        }
                    },
                )
            }
            ui.catalogueError?.let { error ->
                item(span = StaggeredGridItemSpan.FullLine) { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            ui.notice?.let { notice ->
                item(span = StaggeredGridItemSpan.FullLine) {
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
                item(span = StaggeredGridItemSpan.FullLine) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (!ui.loading && firstRun && ui.entries.isNotEmpty() && ui.recommendation.primary == null) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text(if (ui.totalRamMb in 1 until 4096) "Local models need a phone with at least 4 GB of RAM."
                    else "Rivet has no model recommendation for this phone.")
                }
            }
            items(modelEntries, key = { it.id }) { entry ->
                val recommendation = when (entry.id) {
                    ui.recommendation.primary?.id -> "Recommended for your phone"
                    ui.recommendation.alternative?.id -> "Lighter option"
                    else -> null
                }
                val installedModel = installedById[entry.id]
                val job = if (installedModel == null) jobs[entry.id] else null
                ModelRow(
                    entry = entry,
                    fit = ui.fitFor(entry),
                    installed = installedModel,
                    recommendation = recommendation,
                    reason = ui.recommendation.why.takeIf { entry.id == ui.recommendation.primary?.id },
                    onPrimary = {
                        if (installedModel != null) {
                            viewModel.activate(entry)
                            if (firstRun || recommendation != null || ui.entries.none { it.id == entry.id }) onClose()
                        } else requestDownload(entry)
                    },
                    onDelete = { deleteTarget = entry },
                    onExport = { exportTarget = entry; exportLauncher.launch("${entry.name}.gguf") },
                    download = if (job == null) null else {
                        {
                            DownloadControls(job, viewModel.storageWarning(job), viewModel.networkProblem(job),
                                onPause = { viewModel.pause(job) },
                                onResume = { viewModel.resume(job) },
                                onMobile = { viewModel.resume(job, allowMobile = true) },
                                onCancel = { viewModel.cancel(job) })
                        }
                    },
                )
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                UtilityFold(R.drawable.ic_add, "Bring your own GGUF") {
                    Text("Paste a Hugging Face repo or file link. Rivet checks for a GGUF before downloading.")
                    RivetOutlinedButton(onClick = { byoOpen = true }) { Text("Add a model link") }
                }
            }
        }
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
    UtilityLead(
        R.drawable.ic_phone_access,
        "This phone",
        if (totalRamMb > 0) "${formatRam(totalRamMb)} RAM · ${formatRam(availableRamMb)} free now"
        else "Reading memory…",
    ) {
        if (totalRamMb > 0) {
            Text("${formatBytes(freeStorage)} storage · About ${formatParams(ceiling.maxParamsB)} at ${ceiling.contextMax / 1024}K context",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelRow(
    entry: LocalModelEntry,
    fit: ModelFit,
    installed: InstalledLocalModel?,
    recommendation: String?,
    reason: String?,
    onPrimary: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    download: (@Composable () -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UtilityGlyph(R.drawable.ic_models)
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text("${entry.params} · ${formatBytes(entry.size)} · ${entry.contextMax / 1024}K context",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recommendation != null) {
                    Text(recommendation, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (download == null && (installed != null || fit.canDownload)) {
                RivetOutlinedButton(onClick = onPrimary) { Text(if (installed != null) "Use" else "Download") }
            }
        }
        if (download != null) download()
        UtilityFold(R.drawable.ic_info, "Model details", if (installed != null) "Installed" else fit.badge.label()) {
            Text(entry.blurb)
            if (!reason.isNullOrBlank()) Text(reason)
            if (fit.reason.isNotBlank() && fit.reason != reason) Text(fit.reason)
            if (installed != null) {
                Text(downloadVerificationText(installed.checksumVerified))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onExport) { Text("Export") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadControls(
    job: DownloadJob,
    warning: String?,
    network: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMobile: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LinearProgressIndicator(progress = { job.fraction }, modifier = Modifier.fillMaxWidth())
        Text("${formatBytes(job.downloaded)} of ${formatBytes(job.total)} · ${job.status.label()}",
            style = MaterialTheme.typography.bodySmall)
        job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        network?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
