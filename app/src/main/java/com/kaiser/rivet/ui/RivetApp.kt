package com.kaiser.rivet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kaiser.rivet.R

private const val RAIL_MIN_WIDTH_DP = 600

@Composable
fun RivetApp(versionName: String) {
    RivetTheme {
        var current by rememberSaveable { mutableStateOf(RivetDestination.Chat.name) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        val destination = RivetDestination.entries.firstOrNull { it.name == current } ?: RivetDestination.Chat
        val wide = LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP

        Scaffold(
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showSettings = true }) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                }
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        RivetDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == destination,
                                onClick = { current = item.name },
                                icon = { Icon(painterResource(item.iconRes), stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (wide) {
                    NavigationRail {
                        RivetDestination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = item == destination,
                                onClick = { current = item.name },
                                icon = { Icon(painterResource(item.iconRes), stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(destination.emptyTextRes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (showSettings) {
            SettingsDialog(versionName, onDismiss = { showSettings = false })
        }
    }
}

@Composable
private fun SettingsDialog(versionName: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_version, versionName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_provider_pending),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
