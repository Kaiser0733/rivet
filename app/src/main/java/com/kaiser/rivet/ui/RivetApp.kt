package com.kaiser.rivet.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.chat.ChatScreen
import com.kaiser.rivet.ui.provider.ProviderEditor
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.SettingsScreen

private const val RAIL_MIN_WIDTH_DP = 600

@Composable
fun RivetApp(versionName: String, chatViewModel: ChatViewModel, providersViewModel: ProvidersViewModel) {
    RivetTheme {
        var current by rememberSaveable { mutableStateOf(RivetDestination.Chat.name) }
        var screen by rememberSaveable { mutableStateOf("tabs") } // tabs | settings | editor
        val destination = RivetDestination.entries.firstOrNull { it.name == current } ?: RivetDestination.Chat
        val wide = LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP
        val editor by providersViewModel.editorState.collectAsState()
        // After process death the saveable screen restores as "editor" but
        // the editor state resets; an editor without a config id must not
        // render (an empty id could be saved as a bogus provider).
        val editing = screen == "editor" && editor.config.id.isNotEmpty()

        if (screen == "settings" || editing) {
            if (editing) {
                ProviderEditor(viewModel = providersViewModel, onDone = { screen = "tabs" })
            } else {
                SettingsScreen(
                    viewModel = providersViewModel,
                    versionName = versionName,
                    onClose = { screen = "tabs" },
                    onEditProvider = {
                        providersViewModel.startEdit(it)
                        screen = "editor"
                    },
                    onNewProvider = {
                        providersViewModel.startNewProvider(it)
                        screen = "editor"
                    },
                )
            }
            return@RivetTheme
        }

        Scaffold(
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { screen = "settings" }) {
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
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (destination) {
                        RivetDestination.Chat -> ChatScreen(
                            chatViewModel = chatViewModel,
                            providersViewModel = providersViewModel,
                            onOpenSettings = { screen = "settings" },
                        )
                        RivetDestination.Files, RivetDestination.Changes, RivetDestination.Terminal -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(destination.emptyTextRes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
