package com.kaiser.rivet.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.chat.ChatScreen
import com.kaiser.rivet.ui.history.HistoryScreen
import com.kaiser.rivet.ui.provider.ProviderEditor
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.SettingsScreen

@Composable
fun RivetApp(versionName: String, chatViewModel: ChatViewModel,
             providersViewModel: ProvidersViewModel) {
    RivetTheme {
        val view = LocalView.current
        val background = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = background
                window.navigationBarColor = background
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
        var destination by rememberSaveable { mutableStateOf(RivetDestination.Chat) }
        var editing by rememberSaveable { mutableStateOf(false) }
        val saveableStates = rememberSaveableStateHolder()
        val editor by providersViewModel.editorState.collectAsState()
        if (editing && editor.config.id.isEmpty()) editing = false

        BackHandler(destination != RivetDestination.Chat || editing) {
            if (editing) {
                providersViewModel.closeEditor()
                editing = false
            } else destination = RivetDestination.Chat
        }

        RivetBackdrop {
            saveableStates.SaveableStateProvider(if (editing) "provider_editor" else destination.name) {
                if (editing) {
                    ProviderEditor(viewModel = providersViewModel, onDone = { editing = false })
                } else when (destination) {
                    RivetDestination.Chat -> {
                        ChatTopBar(onSettings = { destination = RivetDestination.Settings })
                        ChatScreen(chatViewModel, providersViewModel,
                            onOpenSettings = { destination = RivetDestination.Settings },
                            onOpenHistory = { destination = RivetDestination.History })
                    }
                    RivetDestination.History -> HistoryScreen(
                        viewModel = chatViewModel,
                        onBack = { destination = RivetDestination.Chat },
                    )
                    RivetDestination.Settings -> SettingsScreen(
                        viewModel = providersViewModel,
                        versionName = versionName,
                        onClose = { destination = RivetDestination.Chat },
                        onEditProvider = {
                            providersViewModel.startEdit(it)
                            editing = true
                        },
                        onNewProvider = {
                            providersViewModel.startNewProvider(it)
                            editing = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 58.dp)
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettings) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings),
                tint = MaterialTheme.colorScheme.onBackground)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f), thickness = 1.dp)
}
