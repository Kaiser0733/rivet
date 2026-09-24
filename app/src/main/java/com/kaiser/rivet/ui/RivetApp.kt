package com.kaiser.rivet.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.chat.ChatScreen
import com.kaiser.rivet.ui.provider.ProviderEditor
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.SettingsScreen

@Composable
fun RivetApp(versionName: String, chatViewModel: ChatViewModel,
             providersViewModel: ProvidersViewModel) {
    RivetTheme {
        var destination by rememberSaveable { mutableStateOf(RivetDestination.Chat) }
        var editing by rememberSaveable { mutableStateOf(false) }
        val editor by providersViewModel.editorState.collectAsState()
        if (editing && editor.config.id.isEmpty()) editing = false

        BackHandler(destination == RivetDestination.Settings || editing) {
            if (editing) editing = false else destination = RivetDestination.Chat
        }
        if (editing) {
            ProviderEditor(viewModel = providersViewModel, onDone = { editing = false })
        } else if (destination == RivetDestination.Settings) {
            SettingsScreen(
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
        } else {
            Scaffold(topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { destination = RivetDestination.Settings }) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                }
            }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    ChatScreen(chatViewModel, providersViewModel,
                        onOpenSettings = { destination = RivetDestination.Settings })
                }
            }
        }
    }
}
