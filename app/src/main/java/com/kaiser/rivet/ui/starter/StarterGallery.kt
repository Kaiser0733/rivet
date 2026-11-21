package com.kaiser.rivet.ui.starter

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
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
import com.kaiser.rivet.storage.SecretStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StarterListing(
    val rows: List<StarterOption>,
    val emptyMessage: String,
    val footnote: String?,
)

@Composable
internal fun rememberStarterListing(providerIds: List<String>): StarterListing {
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
    return StarterListing(starterGalleryEntries(bundled, loaded),
        if (failed) "Couldn't open the starters." else "Loading starters…", starterFootnote(hasProviderKey))
}

internal fun LazyListScope.starterRows(listing: StarterListing, enabled: Boolean, onPick: (StarterOption) -> Unit) {
    if (listing.rows.isEmpty()) item {
        Text(listing.emptyMessage, modifier = Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
    items(listing.rows, key = { "starter:${it.starter.id}" }) { option ->
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { onPick(option) },
            headlineContent = { Text(option.starter.name, style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text(option.starter.blurb) },
            trailingContent = {
                Text(starterSize(option.starter.bytes), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    }
    listing.footnote?.let { footnote ->
        item {
            Text(footnote, modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Suppress("DEPRECATION")
private fun starterAppVersion(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    else info.versionCode
}
