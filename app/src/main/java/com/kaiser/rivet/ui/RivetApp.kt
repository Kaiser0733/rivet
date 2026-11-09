package com.kaiser.rivet.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.storage.AutonomyStore
import com.kaiser.rivet.ui.chat.ChatScreen
import com.kaiser.rivet.ui.history.HistoryPane
import com.kaiser.rivet.ui.processes.ToolsPane
import com.kaiser.rivet.ui.provider.ProviderEditor
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.SettingsScreen
import com.kaiser.rivet.ui.provider.SkillInstallDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RivetApp(versionName: String, chatViewModel: ChatViewModel,
             providersViewModel: ProvidersViewModel, processNavigationRequest: Int = 0,
             sharedZip: android.net.Uri? = null, onSharedZipConsumed: () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val appearanceStore = remember(context) { AppearanceStore(context) }
    val autonomyStore = remember(context) { AutonomyStore(context) }
    val storedAppearance by appearanceStore.appearance.collectAsState(initial = RivetAppearance())
    val autonomyMode by autonomyStore.mode.collectAsState(initial = AutonomyMode.Ask)
    var roseIntensity by remember { mutableIntStateOf(storedAppearance.roseIntensity) }
    var appearanceSaveError by remember { mutableStateOf<String?>(null) }
    var themeMode by remember { mutableStateOf(storedAppearance.themeMode) }
    var themeSaving by remember { mutableStateOf(false) }
    var autonomySaving by remember { mutableStateOf(false) }
    var autonomyError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storedAppearance.roseIntensity) { roseIntensity = storedAppearance.roseIntensity }
    LaunchedEffect(storedAppearance.themeMode) { themeMode = storedAppearance.themeMode }
    val coroutineScope = rememberCoroutineScope()

    RivetTheme(themeMode) {
        val view = LocalView.current
        val systemDark = isSystemInDarkTheme()
        val darkTheme = themeMode == RivetThemeMode.Dark ||
            (themeMode == RivetThemeMode.System && systemDark)
        val background = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = background
                window.navigationBarColor = background
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
        var editing by rememberSaveable { mutableStateOf(false) }
        // Panels: the left slide's state is the drawer's own; the right slide and the
        // settings sheet are booleans, so a swipe-close or a Back-close cannot disagree
        // with a separate flag.
        var processesOpen by rememberSaveable { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var installOpen by rememberSaveable { mutableStateOf(false) }
        var installZip by remember { mutableStateOf<android.net.Uri?>(null) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val saveableStates = rememberSaveableStateHolder()
        LaunchedEffect(processNavigationRequest) {
            if (processNavigationRequest > 0) {
                // A notification's deep link opens the tools slide instead of replacing chat.
                if (drawerState.isOpen) drawerState.close()
                settingsOpen = false
                processesOpen = true
            }
        }
        // A zip shared to Rivet lands on the same review sheet as the GitHub route.
        LaunchedEffect(sharedZip) {
            if (sharedZip != null) {
                installZip = sharedZip
                installOpen = true
                onSharedZipConsumed()
            }
        }
        val editor by providersViewModel.editorState.collectAsState()
        if (editing && editor.config.id.isEmpty()) editing = false
        // Stable instances so ChatScreen can skip: these only change shell state, which
        // rememberSaveable keeps across recompositions.
        val openSettings = remember { { settingsOpen = true } }
        val openHistory = remember(coroutineScope, drawerState) {
            { coroutineScope.launch { drawerState.open() }; Unit }
        }
        val closeHistory: () -> Unit = remember(coroutineScope, drawerState) {
            { coroutineScope.launch { drawerState.close() }; Unit }
        }
        val openProcesses = remember { { processesOpen = true } }

        val panelOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open || processesOpen
        BackHandler(editing || settingsOpen || panelOpen) {
            when {
                editing -> {
                    providersViewModel.closeEditor()
                    editing = false
                }
                settingsOpen -> settingsOpen = false
                processesOpen -> processesOpen = false
                else -> closeHistory()
            }
        }

        RivetBackdrop {
            saveableStates.SaveableStateProvider(if (editing) "provider_editor" else "chat") {
                if (editing) {
                    ProviderEditor(viewModel = providersViewModel, onDone = { editing = false })
                } else {
                    // Chat is the permanent base layer: the panels slide over it, so the
                    // conversation (and its scroll position) survives every open and close.
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet {
                                HistoryPane(chatViewModel, Modifier.fillMaxSize(),
                                    onNavigate = closeHistory)
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                                RivetChatBackground()
                                ChatScreen(chatViewModel, providersViewModel,
                                    onOpenSettings = openSettings,
                                    onOpenHistory = openHistory,
                                    onOpenProcesses = openProcesses)
                            }
                            RightSlidePanel(open = processesOpen,
                                onClose = { processesOpen = false }) {
                                val stateLine by chatViewModel.terminalStatusLine.collectAsState()
                                ToolsPane(
                                    processes = chatViewModel.managedProcesses,
                                    terminal = chatViewModel.terminalSession,
                                    stateLine = stateLine,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Settings: one modal sheet over the chat, created only while open. The sheet's own
        // Back/scrim dismiss ends up in onDismissRequest; the detail-to-list step is the
        // screen's own BackHandler, registered deeper.
        if (settingsOpen) {
            ModalBottomSheet(
                onDismissRequest = { settingsOpen = false },
                sheetState = settingsSheetState,
            ) {
                val conversation by chatViewModel.uiState.collectAsState()
                val skillCatalog by chatViewModel.skillCatalog.collectAsState()
                SettingsScreen(
                    conversationUsage = conversation.usage,
                    skillCatalog = skillCatalog,
                    onRefreshSkills = chatViewModel::refreshSkillCatalog,
                    onLoadSkillFiles = chatViewModel::skillBundledFiles,
                    onInstallSkill = {
                        installZip = null
                        installOpen = true
                    },
                    viewModel = providersViewModel,
                    versionName = versionName,
                    onClose = { settingsOpen = false },
                    onEditProvider = {
                        settingsOpen = false
                        providersViewModel.startEdit(it)
                        editing = true
                    },
                    onNewProvider = {
                        settingsOpen = false
                        providersViewModel.startNewProvider(it)
                        editing = true
                    },
                    themeMode = themeMode,
                    themeSaving = themeSaving,
                    onThemeChange = { value ->
                        if (!themeSaving) {
                            themeMode = value
                            themeSaving = true
                            appearanceSaveError = null
                            coroutineScope.launch {
                                try {
                                    appearanceStore.setThemeMode(value)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    themeMode = storedAppearance.themeMode
                                    appearanceSaveError = "Rivet couldn't save this setting. Try again."
                                } finally {
                                    themeSaving = false
                                }
                            }
                        }
                    },
                    appearanceError = appearanceSaveError,
                    autonomyMode = autonomyMode,
                    autonomySaving = autonomySaving,
                    autonomyError = autonomyError,
                    onAutonomyChange = { value ->
                        if (!autonomySaving && value != autonomyMode) {
                            autonomySaving = true
                            autonomyError = null
                            coroutineScope.launch {
                                try {
                                    autonomyStore.setMode(value)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    autonomyError = "Rivet couldn't save this setting. Try again."
                                } finally {
                                    autonomySaving = false
                                }
                            }
                        }
                    },
                )
            }
        }

        if (installOpen) {
            SkillInstallDialog(
                initialZip = installZip,
                onDismiss = {
                    installOpen = false
                    installZip = null
                },
                onInstalled = chatViewModel::refreshSkillCatalog,
            )
        }
    }
}
