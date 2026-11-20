package com.kaiser.rivet.ui.starter

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.kaiser.rivet.storage.SecretStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StarterGallery(providerIds: List<String>, onDismiss: () -> Unit, onPick: (StarterOption) -> Unit) {
    val app = LocalContext.current.applicationContext
    var bundled by remember(app) { mutableStateOf<List<StarterOption>>(emptyList()) }
    var loaded by remember(app) { mutableStateOf<List<StarterOption>>(emptyList()) }
    var failed by remember(app) { mutableStateOf(false) }
    var hasProviderKey by remember(app, providerIds) {
        mutableStateOf<Boolean?>(if (providerIds.isEmpty()) false else null)
    }
    LaunchedEffect(app, providerIds) {
        if (providerIds.isNotEmpty()) {
            hasProviderKey = withContext(Dispatchers.IO) {
                runCatching {
                    val secrets = SecretStore(app)
                    providerIds.any(secrets::hasApiKey)
                }.getOrNull()
            }
        }
    }
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
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text("Starters", modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge)
            Text("Pick a starter, then choose an empty folder.",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            val rows = starterGalleryEntries(bundled, loaded)
            if (rows.isEmpty()) {
                Text(if (failed) "Couldn't open the starters." else "Loading starters…",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(rows, key = { it.starter.id }) { option ->
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { onPick(option) },
                        headlineContent = { Text(option.starter.name) },
                        supportingContent = { Text(option.starter.blurb) },
                        trailingContent = { Text(starterSize(option.starter.bytes)) },
                    )
                }
                starterFootnote(hasProviderKey)?.let { footnote ->
                    item {
                        Text(footnote, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
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
