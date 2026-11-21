package com.kaiser.rivet.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.DrawerState
import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.local.ModelsViewModel
import com.kaiser.rivet.storage.AutonomyStore
import com.kaiser.rivet.ui.browser.BrowserHost
import com.kaiser.rivet.ui.browser.BrowserScreen
import com.kaiser.rivet.ui.browser.findActivity
import com.kaiser.rivet.ui.terminal.TerminalPane
import com.kaiser.rivet.ui.chat.ApprovalDialog
import com.kaiser.rivet.ui.chat.ChatScreen
import com.kaiser.rivet.ui.history.RivetSidebar
import com.kaiser.rivet.provider.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import com.kaiser.rivet.ui.local.ModelsScreen
import com.kaiser.rivet.ui.processes.ToolsScreen
import com.kaiser.rivet.ui.chat.WorkerScreen
import com.kaiser.rivet.ui.chat.workerSheetRows
import com.kaiser.rivet.ui.chat.chipSheetWorker
import com.kaiser.rivet.ui.workspace.ProjectsScreen
import com.kaiser.rivet.ui.provider.ProviderEditor
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import com.kaiser.rivet.ui.provider.SettingsScreen
import com.kaiser.rivet.ui.provider.SkillInstallScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RivetApp(versionName: String, chatViewModel: ChatViewModel,
             providersViewModel: ProvidersViewModel, modelsViewModel: ModelsViewModel,
             processNavigationRequest: Int = 0) {
    val activity = LocalContext.current.findActivity()
    val context = LocalContext.current.applicationContext
    DisposableEffect(chatViewModel.browser, activity) {
        chatViewModel.browser.customTabOpener = { url ->
            activity != null && runCatching {
                CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
            }.isSuccess
        }
        onDispose { chatViewModel.browser.customTabOpener = null }
    }
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
        var destination by rememberSaveable { mutableStateOf(RivetDestination.Chat) }
        var modelsReturn by rememberSaveable { mutableStateOf(RivetDestination.Chat) }
        var installReturn by rememberSaveable { mutableStateOf(RivetDestination.Chat) }
        var installZip by rememberSaveable { mutableStateOf<Uri?>(null) }
        val terminalStateLine by chatViewModel.terminalStatusLine.collectAsState()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val browserState by chatViewModel.browser.state.collectAsState()
        val sendFeedback: () -> Unit = {
            coroutineScope.launch {
                runCatching {
                    chatViewModel.browser.openVisual("https://vastraalabs.online/feedback",
                        userInitiated = true)
                }
            }
        }
        var confirmYolo by rememberSaveable { mutableStateOf(false) }
        val persistAutonomy: (AutonomyMode) -> Unit = { value ->
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
        }
        val requestAutonomy: (AutonomyMode) -> Unit = { value ->
            if (!autonomySaving && value != autonomyMode) {
                if (value == AutonomyMode.Yolo) confirmYolo = true else persistAutonomy(value)
            }
        }
        val saveableStates = rememberSaveableStateHolder()
        LaunchedEffect(processNavigationRequest) {
            if (processNavigationRequest > 0) {
                // A notification's deep link opens the tools slide cleanly above no stale overlays.
                if (drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) drawerState.slideTo(DrawerValue.Closed)
                destination = RivetDestination.Chat
                editing = false
                installZip = null
                if (browserState.visible) chatViewModel.browser.close()
                destination = RivetDestination.Tools
            }
        }
        // A zip shared to Rivet lands on the same review sheet as the GitHub route.
        val sharedZip by chatViewModel.sharedZip.collectAsState()
        val sharedContent by chatViewModel.sharedContent.collectAsState()
        LaunchedEffect(sharedContent.values.lastOrNull()?.id) {
            if (sharedContent.isNotEmpty()) {
                if (drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) drawerState.slideTo(DrawerValue.Closed)
                destination = RivetDestination.Chat
                editing = false
                installZip = null
                if (browserState.visible) chatViewModel.browser.close()
            }
        }
        LaunchedEffect(sharedZip) {
            if (sharedZip != null) {
                drawerState.slideTo(DrawerValue.Closed)
                if (browserState.visible) chatViewModel.browser.close()
                editing = false
                installReturn = RivetDestination.Chat
                installZip = sharedZip
                destination = RivetDestination.SkillInstall
                chatViewModel.clearSharedZip()
            }
        }
        val editor by providersViewModel.editorState.collectAsState()
        val providerList by providersViewModel.listState.collectAsState()
        val planProvider = providerList.configs.firstOrNull { it.type == ProviderType.ChatGptPlan }
        val profileInitial by produceState<String?>(null, planProvider) {
            value = planProvider?.let { provider ->
                withContext(Dispatchers.IO) {
                    runCatching { providersViewModel.chatGptEmail(provider.id) }.getOrNull()
                        ?.firstOrNull(Char::isLetterOrDigit)?.uppercaseChar()?.toString()
                }
            }
        }
        if (editing && editor.config.id.isEmpty()) editing = false
        // Stable instances so ChatScreen can skip: these only change shell state, which
        // rememberSaveable keeps across recompositions.
        val openProjects = remember { { destination = RivetDestination.Projects } }
        val openSettings = remember { { destination = RivetDestination.Settings } }
        val openHistory = remember(coroutineScope, drawerState) {
            { coroutineScope.launch { drawerState.slideTo(DrawerValue.Open) }; Unit }
        }
        val closeHistory: () -> Unit = remember(coroutineScope, drawerState) {
            { coroutineScope.launch { drawerState.slideTo(DrawerValue.Closed) }; Unit }
        }
        val openTools = remember { { destination = RivetDestination.Tools } }
        val openModels: () -> Unit = {
            modelsReturn = destination
            destination = RivetDestination.Models
        }

        val closeSidebarThen: (() -> Unit) -> Unit = { action ->
            coroutineScope.launch {
                drawerState.slideTo(DrawerValue.Closed)
                action()
            }
        }
        val panelOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
        val canDismissPage = editing || destination != RivetDestination.Chat || browserState.visible || panelOpen
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        BackHandler(canDismissPage) {
            when {
                browserState.visible -> chatViewModel.browser.close()
                editing -> {
                    providersViewModel.closeEditor()
                    editing = false
                }
                destination == RivetDestination.Models -> destination = destination.backDestination(modelsReturn)
                destination == RivetDestination.SkillInstall -> {
                    installZip = null
                    destination = destination.backDestination(installReturn)
                }
                destination == RivetDestination.Terminal -> destination = destination.backDestination()
                destination == RivetDestination.Workers -> {
                    chatViewModel.closeWorker()
                    destination = RivetDestination.Tools
                }
                destination != RivetDestination.Chat -> destination = RivetDestination.Chat
                else -> closeHistory()
            }
        }

        val page = if (browserState.visible) "browser" else if (editing) "provider" else destination.name
        val progress = entryProgress(page, animate = page != RivetDestination.Terminal.name,
            duration = RivetMotion.PAGE_MS)
        val offset = if (destination == RivetDestination.Chat && !browserState.visible) RivetMotion.pageBackShift
            else RivetMotion.pageShift
        RivetBackdrop {
            if (!browserState.visible) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.fillMaxSize().dismissOnEscape(canDismissPage) { backDispatcher?.onBackPressed() }.pageEntry(progress, offset)) {
                        if (editing) {
                            saveableStates.SaveableStateProvider("provider_editor") {
                                ProviderEditor(viewModel = providersViewModel, onDone = { editing = false })
                            }
                        } else when (destination) {
                            RivetDestination.Chat -> saveableStates.SaveableStateProvider("chat") {
                                ModalNavigationDrawer(
                                    drawerState = drawerState,
                                    scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = RivetMotion.drawerScrim),
                                    drawerContent = {
                                        ModalDrawerSheet(
                                            modifier = Modifier.widthIn(max = RivetLayout.drawerMaxWidth)
                                                .fillMaxWidth(RivetLayout.drawerWidthFraction),
                                            drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        ) {
                                            RivetSidebar(
                                                viewModel = chatViewModel,
                                                profileInitial = profileInitial,
                                                drawerOpen = drawerState.isOpen,
                                                onNavigate = closeHistory,
                                                onOpenProjects = { closeSidebarThen(openProjects) },
                                                onOpenTools = { closeSidebarThen(openTools) },
                                                onOpenBrowser = {
                                                    closeSidebarThen {
                                                        if (com.kaiser.rivet.browser.BrowserDrivingPolicy.cardVisible(
                                                                browserState.agentRunActive, browserState.agentVisualActive,
                                                                browserState.watchVisible, browserState.visible))
                                                            chatViewModel.browser.watch()
                                                        else chatViewModel.browser.userOpen()
                                                    }
                                                },
                                                onOpenModels = { closeSidebarThen(openModels) },
                                                onOpenSettings = { closeSidebarThen(openSettings) },
                                            )
                                        }
                                    },
                                ) {
                                    ChatScreen(chatViewModel, providersViewModel,
                                        onOpenSettings = openSettings,
                                        onOpenSidebar = openHistory,
                                        onSendFeedback = sendFeedback,
                                        onAutonomyChange = requestAutonomy,
                                        onOpenProjects = openProjects)
                                }
                            }
                            RivetDestination.Tools -> saveableStates.SaveableStateProvider("tools") {
                                val conversation by chatViewModel.uiState.collectAsState()
                                val workers by chatViewModel.subagents.collectAsState()
                                val selected = chipSheetWorker(workers)
                                ToolsScreen(
                                    processes = chatViewModel.managedProcesses,
                                    terminal = chatViewModel.terminalSession,
                                    onBack = { destination = RivetDestination.Chat },
                                    onOpenTerminal = { destination = RivetDestination.Terminal },
                                    onOpenWorkers = selected?.let { { chatViewModel.openWorker(it.id); destination = RivetDestination.Workers } },
                                    checkpoints = conversation.runCheckpoints,
                                    onRestoreCheckpoint = chatViewModel::restoreRunCheckpoint,
                                    checkpointNotice = conversation.runCheckpointNotice,
                                )
                            }
                            RivetDestination.Terminal -> ProtectedControls {
                                TerminalPane(chatViewModel.terminalSession, terminalStateLine,
                                    onClose = { destination = RivetDestination.Tools },
                                    modifier = Modifier.fillMaxSize())
                            }
                            RivetDestination.Workers -> {
                                val workers by chatViewModel.subagents.collectAsState()
                                val sheet by chatViewModel.workerSheet.collectAsState()
                                val worker = workers.firstOrNull { it.id == sheet.worker?.id } ?: sheet.worker
                                if (worker == null) Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                                    RivetPageHeader("Subagents") { destination = RivetDestination.Tools }
                                } else WorkerScreen(
                                    worker = worker,
                                    workers = workerSheetRows(workers),
                                    onSelect = chatViewModel::openWorker,
                                    messages = sheet.messages,
                                    loading = sheet.loading,
                                    error = sheet.error,
                                    onDismiss = { chatViewModel.closeWorker(); destination = RivetDestination.Tools },
                                    onStop = { chatViewModel.stopSubagent(worker.id) },
                                    onContinue = {
                                        chatViewModel.closeWorker()
                                        destination = RivetDestination.Chat
                                        chatViewModel.continueWorker(worker.id)
                                    },
                                )
                            }
                            RivetDestination.Projects -> saveableStates.SaveableStateProvider("projects") {
                                ProjectsScreen(chatViewModel, onBack = { destination = RivetDestination.Chat })
                            }
                            RivetDestination.Models -> saveableStates.SaveableStateProvider("models") {
                                Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                                    ModelsScreen(modelsViewModel, firstRun = providerList.configs.isEmpty(),
                                        onClose = { destination = modelsReturn })
                                }
                            }
                            RivetDestination.SkillInstall -> SkillInstallScreen(
                                initialZip = installZip,
                                onDismiss = {
                                    installZip = null
                                    destination = destination.backDestination(installReturn)
                                },
                                onInstalled = chatViewModel::refreshSkillCatalog,
                            )
                            RivetDestination.Settings -> saveableStates.SaveableStateProvider("settings") {
                                Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                                    val conversation by chatViewModel.uiState.collectAsState()
                                    val skillCatalog by chatViewModel.skillCatalog.collectAsState()
                                    SettingsScreen(
                                        conversationUsage = conversation.usage,
                                        skillCatalog = skillCatalog,
                                        memoryLines = conversation.memoryLines,
                                        memoryMarkdown = conversation.memoryMarkdown,
                                        hasProject = conversation.projectName != null,
                                        onUpdateMemory = chatViewModel::updateMemory,
                                        onForgetMemoryLine = chatViewModel::forgetMemoryLine,
                                        spendCaps = conversation.spendCaps,
                                        userPricePerMillionUsd = conversation.userPricePerMillionUsd,
                                        onSaveSpend = chatViewModel::saveSpendCaps,
                                        onRefreshSkills = chatViewModel::refreshSkillCatalog,
                                        onLoadSkillFiles = chatViewModel::skillBundledFiles,
                                        onInstallSkill = {
                                            installReturn = RivetDestination.Settings
                                            installZip = null
                                            destination = RivetDestination.SkillInstall
                                        },
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
                                        onOpenModels = openModels,
                                        onSendFeedback = sendFeedback,
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
                                        onAutonomyChange = requestAutonomy,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (browserState.visible || (browserState.rendererVisible && !browserState.teardownRequested)) {
                BrowserScreen(
                    chatViewModel.browser,
                    onClose = { chatViewModel.browser.close() },
                    onCloseSession = { chatViewModel.browser.endSession() },
                    modifier = Modifier.fillMaxSize()
                        .then(if (browserState.visible) Modifier.pageEntry(progress, offset) else Modifier)
                        .zIndex(if (browserState.visible) 2f else -1f),
                    hostContent = { hostModifier, onPageInteraction ->
                        BrowserHost(chatViewModel.browser, hostModifier, onPageInteraction)
                    },
                )
            }

        }

        if (confirmYolo) {
            AlertDialog(
                onDismissRequest = { confirmYolo = false },
                title = { Text("Enable YOLO?", style = MaterialTheme.typography.titleMedium) },
                text = { Text("Rivet can edit or delete project files, run project commands, download files, and start allowed processes without asking. Commands run with Rivet's app privileges. Project checkpoints, workspace checks, and Rivet's capability limits still apply.",
                    style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { RivetOutlinedButton(onClick = {
                    confirmYolo = false
                    persistAutonomy(AutonomyMode.Yolo)
                }) { Text("Enable YOLO") } },
                dismissButton = { TextButton(onClick = { confirmYolo = false }) { Text("Cancel") } },
            )
        }

        // Hosted after every sheet and dialog so a pending approval is the top-most
        // window: composed earlier it could sit underneath one and never be seen.
        val approvalState by chatViewModel.uiState.collectAsState()
        approvalState.pendingApproval?.let { approval ->
            ApprovalDialog(approval, chatViewModel::approve, chatViewModel::deny)
        }
    }
}

@Suppress("DEPRECATION")
private suspend fun DrawerState.slideTo(value: DrawerValue) {
    val reducedMotion = kotlinx.coroutines.currentCoroutineContext()[androidx.compose.ui.MotionDurationScale]
        ?.scaleFactor == 0f
    if (reducedMotion) snapTo(value)
    else animateTo(value, androidx.compose.animation.core.tween(RivetMotion.DRAWER_MS, easing = RivetMotion.easing))
}
