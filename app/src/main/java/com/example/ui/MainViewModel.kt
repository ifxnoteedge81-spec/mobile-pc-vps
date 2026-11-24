package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PersonalAiApplication
import com.example.accessibility.InstalledAppSummary
import com.example.accessibility.ObservedUiNode
import com.example.agent.AgentRuntimeState
import com.example.agent.AgentUploadedAttachment
import com.example.ai.GeminiModelInfo
import com.example.ai.OpenRouterModelInfo
import com.example.browser.BrowserTab
import com.example.browser.PageSnapshot
import com.example.resources.DeviceResourceSnapshot
import com.example.runtime.ScriptExecutionResult
import com.example.runtime.TerminalLine
import com.example.runtime.WebServerState
import com.example.runtime.WorkspaceFileItem
import com.example.scheduler.AgentForegroundService
import com.example.security.NetworkEvent
import com.example.security.SecureKeyStore.SecretSlot
import com.example.storage.AgentMemoryEntity
import com.example.storage.AgentTaskEntity
import com.example.storage.AiProviderType
import com.example.storage.AppSettings
import com.example.storage.AutomationRuleEntity
import com.example.storage.BookmarkEntity
import com.example.storage.ExecutionLogEntity
import com.example.storage.PowerProfile
import com.example.storage.ProviderUsageEntity
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PersonalAiApplication).container

    val settings: StateFlow<AppSettings> = container.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val resources: StateFlow<DeviceResourceSnapshot> = container.resourceManager.snapshot
    val agentState: StateFlow<AgentRuntimeState> = container.agentEngine.state
    val terminalLines: StateFlow<List<TerminalLine>> = container.linuxEnv.terminalLines
    val currentShellDir: StateFlow<String> = container.linuxEnv.currentRelativeDir
    val webServerState: StateFlow<WebServerState> = container.webServer.state

    val browserTabs: StateFlow<List<BrowserTab>> = container.browserController.tabs
    val activeBrowserTabId: StateFlow<String> = container.browserController.activeTabId
    val browserSnapshot: StateFlow<PageSnapshot?> = container.browserController.lastExtractedSnapshot
    val browserLogs: StateFlow<List<String>> = container.browserController.automationLog

    val accessibilityConnected: StateFlow<Boolean> = container.deviceController.accessibilityConnected
    val activeWindowPackage: StateFlow<String> = container.deviceController.activePackage
    val liveAccessibilityNodes: StateFlow<List<ObservedUiNode>> = container.deviceController.liveNodes
    val networkEvents: StateFlow<List<NetworkEvent>> = container.auditLogger.events

    val tasks: StateFlow<List<AgentTaskEntity>> = container.repository.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentLogs: StateFlow<List<ExecutionLogEntity>> = container.repository.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memories: StateFlow<List<AgentMemoryEntity>> = container.repository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val automationRules: StateFlow<List<AutomationRuleEntity>> = container.repository.automationRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = container.repository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val providerUsage: StateFlow<List<ProviderUsageEntity>> = container.repository.providerUsage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // File Manager & Code Editor state
    private val _currentBrowsePath = MutableStateFlow("")
    val currentBrowsePath: StateFlow<String> = _currentBrowsePath.asStateFlow()

    private val _files = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val files: StateFlow<List<WorkspaceFileItem>> = _files.asStateFlow()

    private val _editorPath = MutableStateFlow<String?>(null)
    val editorPath: StateFlow<String?> = _editorPath.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    private val _editorRunOutput = MutableStateFlow<ScriptExecutionResult?>(null)
    val editorRunOutput: StateFlow<ScriptExecutionResult?> = _editorRunOutput.asStateFlow()

    // Agent Box file attachments
    private val _pendingAttachments = MutableStateFlow<List<AgentUploadedAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<AgentUploadedAttachment>> = _pendingAttachments.asStateFlow()

    // Terminal Level 2 confirmation prompt
    private val _pendingShellConfirmation = MutableStateFlow<String?>(null)
    val pendingShellConfirmation: StateFlow<String?> = _pendingShellConfirmation.asStateFlow()

    // Live API-discovered model lists (no hardcoded model requirement)
    private val _geminiModels = MutableStateFlow<List<GeminiModelInfo>>(emptyList())
    val geminiModels: StateFlow<List<GeminiModelInfo>> = _geminiModels.asStateFlow()

    private val _openRouterModels = MutableStateFlow<List<OpenRouterModelInfo>>(emptyList())
    val openRouterModels: StateFlow<List<OpenRouterModelInfo>> = _openRouterModels.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    val lastResolvedModel: StateFlow<String?> = container.aiRouter.lastResolvedModel

    // Virtual Hands inspector state
    private val _observedNodes = MutableStateFlow<List<ObservedUiNode>>(emptyList())
    val observedNodes: StateFlow<List<ObservedUiNode>> = _observedNodes.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppSummary>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppSummary>> = _installedApps.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val browserController get() = container.browserController
    val deviceController get() = container.deviceController
    val ramBudgetPresets get() = container.resourceManager.presetRamBudgetsMb

    init {
        refreshResources()
        refreshFiles()
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun showBanner(msg: String) {
        _statusBanner.value = msg
    }

    fun isOnline(): Boolean = container.aiRouter.isOnline()

    fun getActiveModelLabel(): String = container.aiRouter.getActiveLabel(settings.value)

    fun hasSecret(slot: SecretSlot): Boolean = container.keyStore.hasSecret(slot)
    fun getMaskedSecret(slot: SecretSlot): String = container.keyStore.getMaskedPreview(slot)

    fun saveSecret(slot: SecretSlot, value: String) {
        container.keyStore.saveSecret(slot, value)
        showBanner("${slot.label} encrypted in Android Keystore.")
        if (slot == SecretSlot.GEMINI_API_KEY) fetchGeminiModels()
        if (slot == SecretSlot.OPENROUTER_API_KEY) fetchOpenRouterModels()
    }

    fun deleteSecret(slot: SecretSlot) {
        container.keyStore.deleteSecret(slot)
        showBanner("${slot.label} removed.")
    }

    fun refreshResources() {
        viewModelScope.launch {
            container.resourceManager.refresh(settings.value)
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settingsStore.updateThemeMode(mode) }
    }

    fun updateProviderConfig(
        preferredProvider: AiProviderType,
        geminiModel: String,
        openRouterModel: String,
        streaming: Boolean,
        autoFallback: Boolean,
        preferFree: Boolean
    ) {
        viewModelScope.launch {
            container.settingsStore.updateProviderConfig(
                preferredProvider, geminiModel, openRouterModel, streaming, autoFallback, preferFree
            )
            showBanner("AI Model Router configuration saved.")
        }
    }

    fun updateResourceConfig(
        ramBudgetMb: Int,
        lowResourceMode: Boolean,
        powerProfile: PowerProfile,
        maxConcurrency: Int
    ) {
        viewModelScope.launch {
            container.settingsStore.updateResourceConfig(ramBudgetMb, lowResourceMode, powerProfile, maxConcurrency)
            container.resourceManager.refresh(settings.value.copy(
                ramBudgetMb = ramBudgetMb,
                lowResourceMode = lowResourceMode,
                powerProfile = powerProfile,
                maxConcurrency = maxConcurrency
            ))
            showBanner("Resource budget updated (${ramBudgetMb} MB).")
        }
    }

    fun clearStorageDirectory(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.resourceManager.clearDirectorySafely(name)
            container.resourceManager.refresh(settings.value)
            showBanner("Cleared $name safely.")
        }
    }

    fun fetchGeminiModels() {
        viewModelScope.launch {
            val key = container.keyStore.getSecret(SecretSlot.GEMINI_API_KEY)
            if (key.isNullOrBlank()) {
                showBanner("Add a Gemini API key first to discover live models.")
                return@launch
            }
            _isFetchingModels.value = true
            container.geminiProvider.fetchAvailableModels(key, forceRefresh = true).fold(
                onSuccess = { list ->
                    _geminiModels.value = list
                    showBanner("Discovered ${list.size} live Gemini models from your API key.")
                },
                onFailure = { err ->
                    showBanner("Gemini discovery failed: ${err.message}")
                }
            )
            _isFetchingModels.value = false
        }
    }

    fun fetchOpenRouterModels() {
        viewModelScope.launch {
            _isFetchingModels.value = true
            val key = container.keyStore.getSecret(SecretSlot.OPENROUTER_API_KEY)
            val res = container.openRouterProvider.fetchAvailableModels(key)
            res.fold(
                onSuccess = { list ->
                    _openRouterModels.value = list
                    val freeCount = list.count { it.isFree }
                    showBanner("Loaded ${list.size} OpenRouter models ($freeCount free).")
                },
                onFailure = { err ->
                    showBanner("Model discovery failed: ${err.message}")
                }
            )
            _isFetchingModels.value = false
        }
    }

    // Agent Box File Upload & Commands
    fun attachPickedFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val added = mutableListOf<AgentUploadedAttachment>()
            for (uri in uris) {
                runCatching {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val name = queryDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    require(bytes.size <= 10 * 1024 * 1024) { "$name exceeds 10MB upload limit" }
                    val savedFile = container.workspaceManager.saveUploadedFile(name, bytes).getOrThrow()
                    val relPath = container.workspaceManager.relativePathOf(savedFile)
                    val isTextLike = mime.startsWith("text/") ||
                        name.substringAfterLast('.', "").lowercase() in setOf(
                            "txt", "md", "csv", "json", "py", "js", "ts", "html", "css", "xml", "yaml", "yml", "sh", "log", "kt", "java"
                        )
                    val excerpt = if (isTextLike && bytes.isNotEmpty()) {
                        bytes.decodeToString().take(3000)
                    } else null
                    AgentUploadedAttachment(
                        displayName = name,
                        relativePath = relPath,
                        mimeType = mime,
                        sizeBytes = bytes.size.toLong(),
                        textExcerpt = excerpt
                    )
                }.onSuccess { added.add(it) }
                    .onFailure { showBanner("Upload error: ${it.message}") }
            }
            if (added.isNotEmpty()) {
                _pendingAttachments.update { it + added }
                refreshFiles()
                showBanner("${added.size}টি ফাইল এজেন্ট ওয়ার্কস্পেসে আপলোড হয়েছে।")
            }
        }
    }

    fun removePendingAttachment(id: String) {
        _pendingAttachments.update { list -> list.filterNot { it.id == id } }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    fun sendAgentCommand(prompt: String) {
        val currentAttachments = _pendingAttachments.value
        container.agentEngine.submitCommand(prompt, currentAttachments)
        if (currentAttachments.isNotEmpty()) {
            _pendingAttachments.value = emptyList()
        }
    }

    fun pauseAgent() = container.agentEngine.pauseTask()
    fun resumeAgent() = container.agentEngine.resumeTask()
    fun stopAgent() {
        AgentForegroundService.clearInterventionNotification(getApplication())
        container.agentEngine.stopTask()
    }
    fun resolveAgentPermission(approved: Boolean) = container.agentEngine.resolvePermission(approved)

    fun resolveHumanIntervention(userNote: String = "") {
        AgentForegroundService.clearInterventionNotification(getApplication())
        container.agentEngine.resolveHumanIntervention(userNote)
    }

    fun clearAgentFeed() = container.agentEngine.clearFeed()

    fun deleteTask(taskId: Long) {
        viewModelScope.launch { container.repository.deleteTask(taskId) }
    }

    fun observeTaskLogs(taskId: Long) = container.repository.observeLogsForTask(taskId)

    // Terminal Actions
    fun runTerminalCommand(cmd: String, confirmedLevel2: Boolean = false) {
        viewModelScope.launch {
            val res = container.linuxEnv.runInteractiveCommand(cmd, confirmedLevel2)
            if (res.requiresConfirmationReason != null) {
                _pendingShellConfirmation.value = cmd
            } else {
                _pendingShellConfirmation.value = null
            }
            refreshFiles()
        }
    }

    fun dismissShellConfirmation() {
        _pendingShellConfirmation.value = null
    }

    fun toggleWebServer() {
        if (webServerState.value.running) container.webServer.stop() else container.webServer.start(8080)
    }

    // File Manager & Code Editor Actions
    fun refreshFiles(path: String = _currentBrowsePath.value) {
        viewModelScope.launch(Dispatchers.IO) {
            _currentBrowsePath.value = path
            _files.value = container.workspaceManager.listDirectory(path)
        }
    }

    fun searchFiles(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (query.isBlank()) {
                refreshFiles(_currentBrowsePath.value)
            } else {
                _files.value = container.workspaceManager.searchFiles(query)
            }
        }
    }

    fun openFileInEditor(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.workspaceManager.readTextFile(relativePath).fold(
                onSuccess = { text ->
                    _editorPath.value = relativePath
                    _editorContent.value = text
                    _editorRunOutput.value = null
                },
                onFailure = { err -> showBanner("Open failed: ${err.message}") }
            )
        }
    }

    fun updateEditorContent(text: String) {
        _editorContent.value = text
    }

    fun saveEditorFile() {
        val path = _editorPath.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            container.workspaceManager.writeTextFile(path, _editorContent.value).fold(
                onSuccess = {
                    refreshFiles()
                    showBanner("Saved $path")
                },
                onFailure = { showBanner("Save error: ${it.message}") }
            )
        }
    }

    fun closeEditor() {
        _editorPath.value = null
        _editorRunOutput.value = null
    }

    fun createNewWorkspaceFile(name: String, isFolder: Boolean) {
        val prefix = _currentBrowsePath.value.takeIf { it.isNotBlank() }?.let { "$it/" } ?: ""
        val rel = "$prefix${name.trim()}"
        viewModelScope.launch(Dispatchers.IO) {
            val res = if (isFolder) container.workspaceManager.createDirectory(rel)
            else container.workspaceManager.writeTextFile(rel, "")
            res.fold(
                onSuccess = {
                    refreshFiles()
                    if (!isFolder) openFileInEditor(rel)
                },
                onFailure = { showBanner("Create failed: ${it.message}") }
            )
        }
    }

    fun renameWorkspaceFile(relativePath: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.workspaceManager.renameFile(relativePath, newName).fold(
                onSuccess = { refreshFiles() },
                onFailure = { showBanner("Rename error: ${it.message}") }
            )
        }
    }

    fun deleteWorkspaceFile(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.workspaceManager.deleteFile(relativePath).fold(
                onSuccess = {
                    if (_editorPath.value == relativePath) closeEditor()
                    refreshFiles()
                    showBanner("Deleted $relativePath")
                },
                onFailure = { showBanner("Delete failed: ${it.message}") }
            )
        }
    }

    fun importSafDocument(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val name = queryDisplayName(context, uri) ?: "imported_${System.currentTimeMillis()}.txt"
                val content = resolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
                container.workspaceManager.writeTextFile("workspace/$name", content).getOrThrow()
                name
            }.fold(
                onSuccess = {
                    refreshFiles("workspace")
                    showBanner("Imported into workspace/$it")
                },
                onFailure = { showBanner("Import failed: ${it.message}") }
            )
        }
    }

    fun runCurrentEditorFile() {
        val path = _editorPath.value ?: return
        val content = _editorContent.value
        viewModelScope.launch {
            container.workspaceManager.writeTextFile(path, content)
            val ext = path.substringAfterLast(".", "").lowercase()
            val result = when (ext) {
                "py" -> withContext(Dispatchers.IO) { container.pythonEngine.executeScript(content) }
                "js", "ts" -> container.jsEngine.executeJs(content, isTypeScript = ext == "ts")
                "sh" -> {
                    val shellRes = container.linuxEnv.executeCommand("sh ../$path", confirmedLevel2 = false)
                    ScriptExecutionResult(
                        success = shellRes.exitCode == 0,
                        stdout = shellRes.stdout,
                        stderr = shellRes.stderr,
                        engineLabel = "/system/bin/sh",
                        durationMs = 0L
                    )
                }
                else -> ScriptExecutionResult(
                    success = true,
                    stdout = "Saved $path (${content.length} chars). Open in Browser if HTML.",
                    stderr = "",
                    engineLabel = "Workspace File",
                    durationMs = 0L
                )
            }
            _editorRunOutput.value = result
        }
    }

    // Browser helpers
    fun addBookmark(title: String, url: String) {
        viewModelScope.launch {
            container.repository.addBookmark(title, url)
            showBanner("Bookmarked $title")
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { container.repository.deleteBookmark(id) }
    }

    fun extractActiveBrowserDom() {
        viewModelScope.launch {
            container.browserController.extractCurrentPage().onFailure {
                showBanner(it.message ?: "DOM extraction failed")
            }
        }
    }

    fun captureBrowserSnapshot() {
        viewModelScope.launch {
            container.browserController.captureWebViewSnapshot().fold(
                onSuccess = { showBanner("Saved snapshot to $it") },
                onFailure = { showBanner(it.message ?: "Snapshot failed") }
            )
        }
    }

    // Virtual Hands & App Manager
    fun inspectDeviceScreen() {
        container.deviceController.inspectScreen().fold(
            onSuccess = { _observedNodes.value = it },
            onFailure = { showBanner(it.message ?: "Accessibility unavailable") }
        )
    }

    fun testVirtualHandGesture(action: String, x: Float = 500f, y: Float = 500f) {
        val res = when (action) {
            "tap" -> container.deviceController.tap(x, y)
            "swipe" -> container.deviceController.swipe(500f, 1200f, 500f, 500f)
            "scroll_down" -> container.deviceController.scroll("down")
            "scroll_up" -> container.deviceController.scroll("up")
            else -> container.deviceController.globalNav(action)
        }
        res.fold(
            onSuccess = { showBanner(it) },
            onFailure = { showBanner(it.message ?: "Gesture failed") }
        )
    }

    fun openActiveOrTargetApp(pkgHint: String? = null) {
        val res = if (!pkgHint.isNullOrBlank() && pkgHint != "None" && pkgHint != getApplication<Application>().packageName) {
            container.deviceController.launchApp(pkgHint)
        } else {
            container.deviceController.openActiveOrLastApp()
        }
        res.onFailure { showBanner(it.message ?: "Cannot switch to target app") }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = container.deviceController.listLaunchableApps()
        }
    }

    // Memory CRUD
    fun saveMemory(category: String, key: String, content: String, id: Long = 0) {
        viewModelScope.launch {
            container.repository.saveMemory(category, key, content, id).fold(
                onSuccess = { showBanner("Saved memory '$key'") },
                onFailure = { showBanner(it.message ?: "Blocked by Security Policy") }
            )
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { container.repository.deleteMemory(id) }
    }

    // Automation Rules CRUD
    fun createAutomationRule(
        title: String,
        prompt: String,
        scheduleType: String,
        hour: Int,
        minute: Int,
        conditionExpr: String
    ) {
        viewModelScope.launch {
            val id = container.repository.saveAutomationRule(
                AutomationRuleEntity(
                    title = title.trim(),
                    prompt = prompt.trim(),
                    scheduleType = scheduleType,
                    hourOfDay = hour,
                    minuteOfHour = minute,
                    conditionExpr = conditionExpr.trim()
                )
            )
            val saved = container.repository.getAutomationRule(id)
            if (saved != null) container.workScheduler.scheduleRule(saved)
            showBanner("Scheduled '$title' ($scheduleType)")
        }
    }

    fun toggleAutomationRule(rule: AutomationRuleEntity) {
        viewModelScope.launch {
            val updated = rule.copy(enabled = !rule.enabled)
            container.repository.saveAutomationRule(updated)
            container.workScheduler.scheduleRule(updated)
        }
    }

    fun runAutomationNow(rule: AutomationRuleEntity) {
        container.workScheduler.runNow(rule)
        showBanner("Queued '${rule.title}' via WorkManager.")
    }

    fun deleteAutomationRule(ruleId: Long) {
        viewModelScope.launch {
            container.workScheduler.cancelRule(ruleId)
            container.repository.deleteAutomationRule(ruleId)
        }
    }

    fun clearAllLocalData() {
        viewModelScope.launch {
            container.repository.clearAllUserData()
            container.keyStore.clearAllSecrets()
            container.auditLogger.clear()
            showBanner("All local tasks, memory, audit logs, and encrypted keys cleared.")
        }
    }
}
