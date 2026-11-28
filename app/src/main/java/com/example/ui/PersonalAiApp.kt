package com.example.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.security.SecureKeyStore.SecretSlot
import com.example.ui.navigation.AppDestination
import com.example.ui.screens.AboutApplicationScreen
import com.example.ui.screens.AgentMemoryScreen
import com.example.ui.screens.AiModelSettingsScreen
import com.example.ui.screens.AppManagerScreen
import com.example.ui.screens.AutomationManagerScreen
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.CommandCenterScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.FileManagerScreen
import com.example.ui.screens.PermissionsAndSecurityScreen
import com.example.ui.screens.ResourceManagerScreen
import com.example.ui.screens.TaskHistoryScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.screens.VirtualDesktopScreen
import com.example.ui.screens.WhatsAppBotScreen
import com.example.ui.theme.PersonalAiTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonalAiApp(
    viewModel: MainViewModel,
    openLiveAgentSignal: Int = 0
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val terminalLines by viewModel.terminalLines.collectAsStateWithLifecycle()
    val currentShellDir by viewModel.currentShellDir.collectAsStateWithLifecycle()
    val webServerState by viewModel.webServerState.collectAsStateWithLifecycle()
    val browserTabs by viewModel.browserTabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeBrowserTabId.collectAsStateWithLifecycle()
    val browserSnapshot by viewModel.browserSnapshot.collectAsStateWithLifecycle()
    val browserActivitySignal by viewModel.browserActivitySignal.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val accessibilityConnected by viewModel.accessibilityConnected.collectAsStateWithLifecycle()
    val accessibilityHealth by viewModel.accessibilityHealth.collectAsStateWithLifecycle()
    val activeWindowPkg by viewModel.activeWindowPackage.collectAsStateWithLifecycle()
    val liveAccessibilityNodes by viewModel.liveAccessibilityNodes.collectAsStateWithLifecycle()
    val daemonRunning by viewModel.daemonRunning.collectAsStateWithLifecycle()
    val waNotificationBound by viewModel.waNotificationBound.collectAsStateWithLifecycle()
    val batteryUnrestricted by viewModel.batteryUnrestricted.collectAsStateWithLifecycle()
    val observedNodes by viewModel.observedNodes.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val currentBrowsePath by viewModel.currentBrowsePath.collectAsStateWithLifecycle()
    val editorPath by viewModel.editorPath.collectAsStateWithLifecycle()
    val editorContent by viewModel.editorContent.collectAsStateWithLifecycle()
    val editorRunOutput by viewModel.editorRunOutput.collectAsStateWithLifecycle()
    val pendingShellConfirm by viewModel.pendingShellConfirmation.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val recentLogs by viewModel.recentLogs.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val automationRules by viewModel.automationRules.collectAsStateWithLifecycle()
    val networkEvents by viewModel.networkEvents.collectAsStateWithLifecycle()
    val geminiModels by viewModel.geminiModels.collectAsStateWithLifecycle()
    val openRouterModels by viewModel.openRouterModels.collectAsStateWithLifecycle()
    val lastResolvedModel by viewModel.lastResolvedModel.collectAsStateWithLifecycle()
    val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()
    val providerUsage by viewModel.providerUsage.collectAsStateWithLifecycle()
    val importedSkills by viewModel.importedSkills.collectAsStateWithLifecycle()
    val skillImportProgress by viewModel.skillImportProgress.collectAsStateWithLifecycle()
    val whatsAppChats by viewModel.whatsAppChats.collectAsStateWithLifecycle()
    val whatsAppActivity by viewModel.whatsAppActivity.collectAsStateWithLifecycle()
    val whatsAppSchedules by viewModel.whatsAppSchedules.collectAsStateWithLifecycle()
    val whatsAppState by viewModel.whatsAppState.collectAsStateWithLifecycle()
    val whatsAppLinkStatus by viewModel.whatsAppLinkStatus.collectAsStateWithLifecycle()
    val whatsAppPairingCode by viewModel.whatsAppPairingCode.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val miniAiStats by viewModel.miniAiStats.collectAsStateWithLifecycle()
    val miniAiSamples by viewModel.miniAiSamples.collectAsStateWithLifecycle()
    val lastCrashReport by viewModel.lastCrashReport.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = AppDestination.fromRoute(backStackEntry?.destination?.route)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemRuntimeHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBanner()
        }
    }

    fun navigateTo(dest: AppDestination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(openLiveAgentSignal, agentState.pendingIntervention?.id) {
        if ((openLiveAgentSignal > 0 || agentState.pendingIntervention != null) &&
            currentDestination != AppDestination.CommandCenter
        ) {
            navigateTo(AppDestination.CommandCenter)
        }
    }

    PersonalAiTheme(themeMode = settings.themeMode) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "PERSONAL AI COMPUTER",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                        AppDestination.allDestinations.forEach { dest ->
                            NavigationDrawerItem(
                                label = { Text(dest.title) },
                                selected = currentDestination == dest,
                                onClick = { navigateTo(dest) },
                                icon = { Icon(dest.icon, contentDescription = null) },
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .testTag("drawer_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWideScreen = maxWidth >= 700.dp
                val isImeVisible = WindowInsets.isImeVisible

                Scaffold(
                    contentWindowInsets = WindowInsets.systemBars,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = { Text(currentDestination.title, style = MaterialTheme.typography.titleMedium) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            navigationIcon = {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.testTag("open_drawer_button")
                                ) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Open all 14 modules menu")
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { navigateTo(AppDestination.AiModelSettings) },
                                    modifier = Modifier.testTag("topbar_model_settings")
                                ) {
                                    Icon(Icons.Filled.Tune, contentDescription = "AI Model Settings")
                                }
                            }
                        )
                    },
                    bottomBar = {
                        if (!isWideScreen && !isImeVisible) {
                            NavigationBar {
                                AppDestination.primaryDestinations.forEach { dest ->
                                    NavigationBarItem(
                                        selected = currentDestination == dest,
                                        onClick = { navigateTo(dest) },
                                        icon = { Icon(dest.icon, contentDescription = dest.title) },
                                        label = { Text(dest.shortLabel) },
                                        modifier = Modifier.testTag("bottom_nav_${dest.route}")
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .imePadding()
                    ) {
                        if (isWideScreen) {
                            NavigationRail {
                                AppDestination.primaryDestinations.forEach { dest ->
                                    NavigationRailItem(
                                        selected = currentDestination == dest,
                                        onClick = { navigateTo(dest) },
                                        icon = { Icon(dest.icon, contentDescription = dest.title) },
                                        label = { Text(dest.shortLabel) }
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                        }

                        Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                            availableUpdate?.takeIf { it.isUpdateAvailable }?.let { update ->
                                androidx.compose.material3.Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    tonalElevation = 2.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "নতুন আপডেট (${update.latestVersion}) — আনইনস্টল ছাড়াই আপডেট করুন",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                        androidx.compose.material3.TextButton(
                                            onClick = { viewModel.launchInPlaceUpdate(update.downloadUrl) },
                                            modifier = Modifier.testTag("banner_in_place_update_button")
                                        ) {
                                            Text("Update")
                                        }
                                        androidx.compose.material3.TextButton(onClick = viewModel::dismissUpdateNotice) {
                                            Text("Later")
                                        }
                                    }
                                }
                            }
                            NavHost(
                                navController = navController,
                                startDestination = AppDestination.Home.route,
                                modifier = Modifier.weight(1f)
                            ) {
                            composable(AppDestination.Home.route) {
                                DashboardScreen(
                                    settings = settings,
                                    resources = resources,
                                    agentState = agentState,
                                    activeModelLabel = viewModel.getActiveModelLabel(),
                                    isOnline = viewModel.isOnline(),
                                    accessibilityConnected = accessibilityConnected,
                                    tasks = tasks,
                                    automations = automationRules,
                                    recentLogs = recentLogs,
                                    voiceState = voiceState,
                                    onRefreshResources = viewModel::refreshResources,
                                    onNavigate = ::navigateTo,
                                    onLaunchSkillPrompt = viewModel::sendAgentCommand
                                )
                            }
                            composable(AppDestination.CommandCenter.route) {
                                CommandCenterScreen(
                                    agentState = agentState,
                                    activeModelLabel = viewModel.getActiveModelLabel(),
                                    isOnline = viewModel.isOnline(),
                                    hasAnyApiKey = viewModel.hasAnyGeminiKey() ||
                                        viewModel.hasSecret(SecretSlot.OPENROUTER_API_KEY),
                                    pendingAttachments = pendingAttachments,
                                    importedSkills = importedSkills,
                                    skillImportProgress = skillImportProgress,
                                    browserController = viewModel.browserController,
                                    browserTabs = browserTabs,
                                    activeTabId = activeTabId,
                                    browserActivitySignal = browserActivitySignal,
                                    accessibilityConnected = accessibilityConnected,
                                    accessibilityHealth = accessibilityHealth,
                                    activeWindowPackage = activeWindowPkg,
                                    liveNodes = liveAccessibilityNodes.ifEmpty { observedNodes },
                                    voiceState = voiceState,
                                    voiceLockEnabled = settings.voiceLockEnabled,
                                    voiceMatchThreshold = settings.voiceMatchThreshold,
                                    voiceSpokenFeedback = settings.voiceSpokenFeedbackEnabled,
                                    onAttachFiles = { uris -> viewModel.attachPickedFiles(context, uris) },
                                    onRemoveAttachment = viewModel::removePendingAttachment,
                                    onAbsorbAttachmentsAsSkills = viewModel::absorbPendingAttachmentsAsSkills,
                                    onImportSkillFiles = { uris -> viewModel.importSkillFromUris(context, uris) },
                                    onImportSkillSource = { src -> viewModel.importSkillFromSource(src) },
                                    onToggleImportedSkill = viewModel::toggleImportedSkill,
                                    onDeleteImportedSkill = viewModel::deleteImportedSkill,
                                    onClearAllImportedSkills = viewModel::clearAllImportedSkills,
                                    onSearchSkillsLocal = { q -> viewModel.searchImportedSkillsLocal(q, importedSkills) },
                                    onEnrollVoice = viewModel::enrollOwnerVoiceSample,
                                    onTestVoiceMatch = viewModel::testOwnerVoiceMatch,
                                    onClearVoice = viewModel::clearOwnerVoice,
                                    onToggleHandsFree = viewModel::toggleVoiceHandsFree,
                                    onUpdateVoicePrefs = viewModel::updateVoicePreferences,
                                    onSendCommand = viewModel::sendAgentCommand,
                                    onPause = viewModel::pauseAgent,
                                    onResume = viewModel::resumeAgent,
                                    onStop = viewModel::stopAgent,
                                    onResolvePermission = viewModel::resolveAgentPermission,
                                    onResolveIntervention = viewModel::resolveHumanIntervention,
                                    onOpenAccessibilitySettings = { viewModel.deviceController.openAccessibilitySettings() },
                                    onOpenAppInfoSettings = viewModel::openOwnAppInfoRestrictedSettings,
                                    onOpenExternalApp = viewModel::openActiveOrTargetApp,
                                    onInspectDeviceScreen = viewModel::inspectDeviceScreen,
                                    onQuickVirtualHand = { act -> viewModel.testVirtualHandGesture(act) },
                                    onClearFeed = viewModel::clearAgentFeed,
                                    onOpenModelSettings = { navigateTo(AppDestination.AiModelSettings) },
                                    miniAiStats = miniAiStats
                                )
                            }
                            composable(AppDestination.WhatsAppBot.route) {
                                WhatsAppBotScreen(
                                    settings = settings,
                                    waState = whatsAppState,
                                    linkStatus = whatsAppLinkStatus,
                                    pairingCode = whatsAppPairingCode,
                                    activeModelLabel = viewModel.getActiveModelLabel(),
                                    hasAnyApiKey = viewModel.hasAnyGeminiKey() ||
                                        viewModel.hasSecret(SecretSlot.OPENROUTER_API_KEY),
                                    chats = whatsAppChats,
                                    activityLogs = whatsAppActivity,
                                    schedules = whatsAppSchedules,
                                    webBridge = viewModel.whatsAppWebBridge,
                                    observeChatMessages = viewModel::observeWhatsAppChatMessages,
                                    onRefreshStatus = viewModel::refreshWhatsAppStatus,
                                    onToggleMaster = viewModel::toggleWhatsAppBotMaster,
                                    onSaveGlobalRules = viewModel::updateWhatsAppGlobalRules,
                                    onRequestPhoneLinkCode = viewModel::requestWhatsAppPhoneLinkCode,
                                    onSyncChats = viewModel::syncWhatsAppChats,
                                    onReloadWeb = viewModel::reloadWhatsAppWeb,
                                    onOpenNotificationSettings = viewModel::openWhatsAppNotificationSettings,
                                    onSendMessage = viewModel::sendWhatsAppMessage,
                                    onUpdateChatRules = viewModel::updateWhatsAppChatRules,
                                    onDeleteChat = viewModel::deleteWhatsAppChatRecord,
                                    onScheduleCampaign = viewModel::scheduleWhatsAppCampaign,
                                    onRunScheduleNow = viewModel::runWhatsAppScheduleNow,
                                    onCancelSchedule = viewModel::cancelWhatsAppSchedule,
                                    onDeleteSchedule = viewModel::deleteWhatsAppSchedule,
                                    onOpenModelSettings = { navigateTo(AppDestination.AiModelSettings) },
                                    waNotificationBound = waNotificationBound,
                                    batteryUnrestricted = batteryUnrestricted,
                                    onOpenAppInfoSettings = viewModel::openOwnAppInfoRestrictedSettings,
                                    onOpenBatterySettings = viewModel::openBatteryUnrestrictedSettings
                                )
                            }
                            composable(AppDestination.VirtualDesktop.route) {
                                VirtualDesktopScreen(
                                    agentState = agentState,
                                    browserTabs = browserTabs,
                                    browserSnapshot = browserSnapshot,
                                    terminalLines = terminalLines,
                                    workspaceFiles = files,
                                    accessibilityConnected = accessibilityConnected,
                                    accessibilityHealth = accessibilityHealth,
                                    activeWindowPackage = activeWindowPkg,
                                    observedNodes = observedNodes,
                                    onOpenAccessibilitySettings = { viewModel.deviceController.openAccessibilitySettings() },
                                    onOpenAppInfoSettings = viewModel::openOwnAppInfoRestrictedSettings,
                                    onInspectScreen = viewModel::inspectDeviceScreen,
                                    onTestGesture = { viewModel.testVirtualHandGesture(it) },
                                    onNavigate = ::navigateTo
                                )
                            }
                            composable(AppDestination.Browser.route) {
                                BrowserScreen(
                                    browserController = viewModel.browserController,
                                    tabs = browserTabs,
                                    activeTabId = activeTabId,
                                    snapshot = browserSnapshot,
                                    bookmarks = bookmarks,
                                    onExtractDom = viewModel::extractActiveBrowserDom,
                                    onCaptureSnapshot = viewModel::captureBrowserSnapshot,
                                    onAddBookmark = viewModel::addBookmark
                                )
                            }
                            composable(AppDestination.Terminal.route) {
                                TerminalScreen(
                                    lines = terminalLines,
                                    currentDir = currentShellDir,
                                    webServerState = webServerState,
                                    pendingConfirmationCmd = pendingShellConfirm,
                                    onRunCommand = viewModel::runTerminalCommand,
                                    onDismissConfirmation = viewModel::dismissShellConfirmation,
                                    onToggleWebServer = viewModel::toggleWebServer,
                                    onOpenLocalServerInBrowser = { url ->
                                        viewModel.browserController.newTab(url)
                                        navigateTo(AppDestination.Browser)
                                    }
                                )
                            }
                            composable(AppDestination.FileManager.route) {
                                FileManagerScreen(
                                    currentPath = currentBrowsePath,
                                    files = files,
                                    editorPath = editorPath,
                                    editorContent = editorContent,
                                    editorRunOutput = editorRunOutput,
                                    onBrowsePath = viewModel::refreshFiles,
                                    onSearch = viewModel::searchFiles,
                                    onOpenFile = viewModel::openFileInEditor,
                                    onEditorChange = viewModel::updateEditorContent,
                                    onSaveEditor = viewModel::saveEditorFile,
                                    onRunEditorFile = viewModel::runCurrentEditorFile,
                                    onCloseEditor = viewModel::closeEditor,
                                    onCreateItem = viewModel::createNewWorkspaceFile,
                                    onRenameItem = viewModel::renameWorkspaceFile,
                                    onDeleteItem = viewModel::deleteWorkspaceFile,
                                    onImportUri = { uri -> viewModel.importSafDocument(context, uri) }
                                )
                            }
                            composable(AppDestination.AppManager.route) {
                                AppManagerScreen(
                                    apps = installedApps,
                                    onLoadApps = viewModel::loadInstalledApps,
                                    onLaunchApp = { pkg ->
                                        viewModel.deviceController.launchApp(pkg).onFailure {
                                            viewModel.showBanner(it.message ?: "Unable to launch app")
                                        }
                                    },
                                    onOpenAppSettings = { pkg -> viewModel.deviceController.openAppDetailsSettings(pkg) }
                                )
                            }
                            composable(AppDestination.AutomationManager.route) {
                                AutomationManagerScreen(
                                    rules = automationRules,
                                    onCreateRule = viewModel::createAutomationRule,
                                    onToggleRule = viewModel::toggleAutomationRule,
                                    onRunNow = viewModel::runAutomationNow,
                                    onDeleteRule = viewModel::deleteAutomationRule
                                )
                            }
                            composable(AppDestination.AgentMemory.route) {
                                AgentMemoryScreen(
                                    memories = memories,
                                    onSaveMemory = viewModel::saveMemory,
                                    onDeleteMemory = viewModel::deleteMemory
                                )
                            }
                            composable(AppDestination.TaskHistory.route) {
                                TaskHistoryScreen(
                                    tasks = tasks,
                                    observeLogs = viewModel::observeTaskLogs,
                                    onDeleteTask = viewModel::deleteTask,
                                    onRerunPrompt = { prompt ->
                                        viewModel.sendAgentCommand(prompt)
                                        navigateTo(AppDestination.CommandCenter)
                                    }
                                )
                            }
                            composable(AppDestination.AiModelSettings.route) {
                                AiModelSettingsScreen(
                                    settings = settings,
                                    geminiModels = geminiModels,
                                    openRouterModels = openRouterModels,
                                    lastResolvedModel = lastResolvedModel,
                                    isFetchingModels = isFetchingModels,
                                    usageHistory = providerUsage,
                                    getMaskedSecret = viewModel::getMaskedSecret,
                                    openRouterKeyMasked = viewModel.getMaskedSecret(SecretSlot.OPENROUTER_API_KEY),
                                    youtubeTokenMasked = viewModel.getMaskedSecret(SecretSlot.YOUTUBE_OAUTH_TOKEN),
                                    onSaveSecret = viewModel::saveSecret,
                                    onDeleteSecret = viewModel::deleteSecret,
                                    onFetchGeminiModels = { viewModel.fetchGeminiModels() },
                                    onFetchOpenRouterModels = viewModel::fetchOpenRouterModels,
                                    onSaveProviderConfig = viewModel::updateProviderConfig,
                                    onChangeTheme = viewModel::updateThemeMode,
                                    miniAiStats = miniAiStats,
                                    miniAiSamples = miniAiSamples,
                                    onUpdateMiniAiPrefs = viewModel::updateMiniAiPrefs,
                                    onTeachMiniAi = viewModel::trainMiniAi,
                                    onDeleteMiniAiSample = viewModel::deleteMiniAiSample,
                                    onResetMiniAi = viewModel::resetMiniAiKnowledge
                                )
                            }
                            composable(AppDestination.ResourceManager.route) {
                                ResourceManagerScreen(
                                    settings = settings,
                                    resources = resources,
                                    presetsMb = viewModel.ramBudgetPresets,
                                    storagePresetsMb = viewModel.storageQuotaPresets,
                                    onRefresh = viewModel::refreshResources,
                                    onSaveResourceConfig = viewModel::updateResourceConfig,
                                    onClearDir = viewModel::clearStorageDirectory
                                )
                            }
                            composable(AppDestination.PermissionsSecurity.route) {
                                PermissionsAndSecurityScreen(
                                    accessibilityConnected = accessibilityConnected,
                                    accessibilityHealth = accessibilityHealth,
                                    backgroundKeepAliveEnabled = settings.backgroundKeepAliveEnabled,
                                    daemonRunning = daemonRunning,
                                    batteryUnrestricted = batteryUnrestricted,
                                    waNotificationBound = waNotificationBound,
                                    networkEvents = networkEvents,
                                    onOpenAccessibilitySettings = { viewModel.deviceController.openAccessibilitySettings() },
                                    onToggleBackgroundKeepAlive = viewModel::toggleBackgroundKeepAlive,
                                    onOpenBatterySettings = viewModel::openBatteryUnrestrictedSettings,
                                    onOpenAppInfoSettings = viewModel::openOwnAppInfoRestrictedSettings,
                                    onOpenNotificationSettings = viewModel::openWhatsAppNotificationSettings,
                                    onClearAllData = viewModel::clearAllLocalData
                                )
                            }
                            composable(AppDestination.About.route) {
                                AboutApplicationScreen(
                                    installedIdentity = viewModel.installedAppIdentity,
                                    availableUpdate = availableUpdate,
                                    isCheckingUpdate = isCheckingUpdate,
                                    onCheckForUpdate = { viewModel.checkForAppUpdate(silent = false) },
                                    onDownloadInPlaceUpdate = viewModel::launchInPlaceUpdate,
                                    lastCrashReport = lastCrashReport,
                                    onClearCrashReport = viewModel::clearLastCrashReport
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}
