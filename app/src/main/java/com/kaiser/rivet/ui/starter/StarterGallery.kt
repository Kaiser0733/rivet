package com.kaiser.rivet.ui.starter

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.workspace.projectFolderPickerIntent
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.starter.StarterCatalog
import com.kaiser.rivet.starter.StarterChannel
import com.kaiser.rivet.starter.StarterOption
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StarterListing(
    val rows: List<StarterOption>,
    val emptyMessage: String,
)

@Composable
private fun rememberStarterListing(): StarterListing {
    val app = LocalContext.current.applicationContext
    var bundled by remember(app) { mutableStateOf<List<StarterOption>>(emptyList()) }
    var loaded by remember(app) { mutableStateOf<List<StarterOption>>(emptyList()) }
    var failed by remember(app) { mutableStateOf(false) }
    LaunchedEffect(app) {
        try {
            val entries = withContext(Dispatchers.IO) {
                app.assets.open(StarterCatalog.ASSET_NAME).bufferedReader().use {
                    StarterCatalog.parse(it.readText())
                }
            }
            bundled = entries.map(::StarterOption)
            loaded = bundled
            val version = withContext(Dispatchers.IO) { starterAppVersion(app) }
            loaded = StarterChannel(File(app.cacheDir, "starter-channel"), version).load(entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = bundled.isEmpty()
        }
    }
    return StarterListing(starterGalleryEntries(bundled, loaded),
        if (failed) "Couldn't open the starters." else "Loading starters…")
}

private val homeStarterGlyphs = linkedMapOf(
    "voxel-world" to R.drawable.ic_starter_voxel,
    "open-city" to R.drawable.ic_starter_city,
    "my-ai" to R.drawable.ic_starter_assistant,
    "idea-lab" to R.drawable.ic_starter_idea,
)

@Composable
internal fun HomeStarters(viewModel: ChatViewModel, enabled: Boolean, error: String?) {
    val listing = rememberStarterListing()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val starter = viewModel.takePendingStarter()
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            if (starter == null) viewModel.starterPickerInterrupted()
            else viewModel.installStarter(uri, result.data?.flags ?: 0, starter)
        }
    }
    val rows = listing.rows.associateBy { it.starter.id }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (rows.isEmpty()) Text(listing.emptyMessage, style = MaterialTheme.typography.bodySmall)
        homeStarterGlyphs.forEach { (id, icon) ->
            val row = rows[id] ?: return@forEach
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button) {
                    viewModel.setPendingStarter(row)
                    try {
                        picker.launch(projectFolderPickerIntent())
                    } catch (failure: RuntimeException) {
                        if (failure !is ActivityNotFoundException && failure !is SecurityException) throw failure
                        viewModel.takePendingStarter()
                        viewModel.starterPickerInterrupted()
                    }
                }.padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(row.starter.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun starterAppVersion(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    else info.versionCode
}
