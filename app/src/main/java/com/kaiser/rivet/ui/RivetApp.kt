package com.kaiser.rivet.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.kaiser.rivet.chat.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.kaiser.rivet.ui.chat.ChatScreen
import com.kaiser.rivet.ui.history.HistoryScreen
import com.kaiser.rivet.ui.provider.ProviderEditor
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.SettingsScreen

@Composable
fun RivetApp(versionName: String, chatViewModel: ChatViewModel,
             providersViewModel: ProvidersViewModel) {
    val context = LocalContext.current.applicationContext
    val appearanceStore = remember(context) { AppearanceStore(context) }
    val storedIntensity by appearanceStore.roseIntensity.collectAsState(initial = DEFAULT_ROSE_INTENSITY)
    var roseIntensity by remember { mutableIntStateOf(storedIntensity) }
    var appearanceSaveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storedIntensity) { roseIntensity = storedIntensity }
    val coroutineScope = rememberCoroutineScope()

    RivetTheme(roseIntensity) {
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
                    RivetDestination.Chat -> Box(Modifier.fillMaxSize()) {
                        RivetChatBackground(intensity = roseIntensity)
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
                        roseIntensity = roseIntensity,
                        appearanceError = appearanceSaveError,
                        onRoseIntensityPreview = { roseIntensity = it },
                        onRoseIntensityCommit = { value ->
                            appearanceSaveError = null
                            coroutineScope.launch {
                                try {
                                    appearanceStore.setRoseIntensity(value)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    roseIntensity = storedIntensity
                                    appearanceSaveError = "Rivet couldn't save this setting. Try again."
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
