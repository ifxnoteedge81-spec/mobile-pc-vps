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
import com.example.security.SecureKeyStore
import com.example.security.SecureKeyStore.SecretSlot
import com.example.storage.AgentMemoryEntity
import com.example.storage.AgentTaskEntity
import com.example.storage.AiProviderType
import com.example.storage.AppSettings
import com.example.storage.AutomationRuleEntity
import com.example.storage.BookmarkEntity
import com.example.storage.ExecutionLogEntity
import com.example.storage.ImportedSkillEntity
import com.example.storage.PowerProfile
import com.example.storage.ProviderUsageEntity
import com.example.storage.WhatsAppChatEntity
import com.example.storage.WhatsAppMessageEntity
import com.example.ui.theme.ThemeMode
import com.example.whatsapp.WaLinkStatus
import com.example.whatsapp.WaRuntimeState
import com.example.whatsapp.WhatsAppNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val browserActivitySignal: StateFlow<Long> = container.browserController.browserActivitySignal

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
    val importedSkills: StateFlow<List<ImportedSkillEntity>> = container.repository.importedSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _skillImportProgress = MutableStateFlow<String?>(null)
    val skillImportProgress: StateFlow<String?> = _skillImportProgress.asStateFlow()
    val whatsAppChats: StateFlow<List<WhatsAppChatEntity>> = container.repository.whatsAppChats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val whatsAppActivity: StateFlow<List<WhatsAppMessageEntity>> = container.repository.whatsAppActivity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val whatsAppState: StateFlow<WaRuntimeState> = container.whatsAppBotEngine.state
    val whatsAppLinkStatus: StateFlow<WaLinkStatus> = container.whatsAppWebBridge.linkStatus
    val whatsAppPairingCode: StateFlow<String> = container.whatsAppWebBridge.pairingCode

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

    val installedAppIdentity: com.example.runtime.InstalledAppIdentity =
        container.updateChecker.getInstalledIdentity()

    private val _availableUpdate = MutableStateFlow<com.example.runtime.AppUpdateInfo?>(null)
    val availableUpdate: StateFlow<com.example.runtime.AppUpdateInfo?> = _availableUpdate.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    val browserController get() = container.browserController
    val deviceController get() = container.deviceController
    val whatsAppWebBridge get() = container.whatsAppWebBridge
    val ramBudgetPresets get() = container.resourceManager.presetRamBudgetsMb
    val storageQuotaPresets get() = container.resourceManager.presetStorageQuotasMb

    init {
        viewModelScope.launch {
            val current = container.settingsStore.settings.first()
            container.resourceManager.refresh(current)
            if (current.pendingRestartNotice) {
                container.settingsStore.clearRestartNotice()
                val geminiKeyCount = container.keyStore.getConfiguredGeminiKeys().size
                val hasOpenRouter = container.keyStore.hasSecret(SecretSlot.OPENROUTER_API_KEY)
                val totalKeys = geminiKeyCount + if (hasOpenRouter) 1 else 0
                showBanner(
                    "রিস্টার্ট সম্পন্ন: RAM ${current.ramBudgetMb} MB ও ROM ${current.storageQuotaMb} MB সক্রিয় — ${totalKeys}টি API Key ও সব ডেটা/কোড সুরক্ষিত আছে।"
                )
            }
        }
        refreshFiles()
        checkForAppUpdate(silent = true)
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        if (_isCheckingUpdate.value) return
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            container.updateChecker.checkLatestRelease().fold(
                onSuccess = { info ->
                    if (info.isUpdateAvailable) {
                        _availableUpdate.value = info
                        if (!silent) {
                            showBanner("নতুন আপডেট (${info.latestVersion}) পাওয়া গেছে — আনইনস্টল ছাড়াই ইন-প্লেস আপডেট করুন।")
                        }
                    } else if (!silent) {
                        _availableUpdate.value = null
                        showBanner("আপনি সর্বশেষ ভার্সন (${installedAppIdentity.versionName}) ব্যবহার করছেন।")
                    }
                },
                onFailure = { err ->
                    if (!silent) {
                        showBanner("আপডেট চেক ব্যর্থ: ${err.message}")
                    }
                }
            )
            _isCheckingUpdate.value = false
        }
    }

    fun dismissUpdateNotice() {
        _availableUpdate.value = null
    }

    fun launchInPlaceUpdate(downloadUrl: String) {
        container.updateChecker.openInPlaceUpdateDownload(downloadUrl).onFailure { err ->
            showBanner("ডাউনলোড লিংক খুলতে ব্যর্থ: ${err.message}")
        }
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
    fun hasAnyGeminiKey(): Boolean = container.keyStore.hasAnyGeminiKey()
    fun getMaskedSecret(slot: SecretSlot): String = container.keyStore.getMaskedPreview(slot)

    fun saveSecret(slot: SecretSlot, value: String) {
        container.keyStore.saveSecret(slot, value)
        container.aiRouter.invalidateKeyCaches()
        showBanner("${slot.label} encrypted in Android Keystore.")
        if (slot in SecureKeyStore.geminiSlots) fetchGeminiModels(preferredSlot = slot)
        if (slot == SecretSlot.OPENROUTER_API_KEY) fetchOpenRouterModels()
    }

    fun deleteSecret(slot: SecretSlot) {
        container.keyStore.deleteSecret(slot)
        container.aiRouter.invalidateKeyCaches()
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
        preferFree: Boolean,
        fullAutonomousMode: Boolean = true
    ) {
        viewModelScope.launch {
            container.settingsStore.updateProviderConfig(
                preferredProvider, geminiModel, openRouterModel, streaming, autoFallback, preferFree, fullAutonomousMode
            )
            showBanner("AI Model Router configuration saved.")
        }
    }

    fun updateResourceConfig(
        ramBudgetMb: Int,
        storageQuotaMb: Int,
        adaptiveDeviceScaling: Boolean,
        lowResourceMode: Boolean,
        powerProfile: PowerProfile,
        maxConcurrency: Int,
        restartAppAfterSave: Boolean = false
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.settingsStore.updateResourceConfig(
                    ramBudgetMb = ramBudgetMb,
                    storageQuotaMb = storageQuotaMb,
                    adaptiveDeviceScaling = adaptiveDeviceScaling,
                    lowResourceMode = lowResourceMode,
                    powerProfile = powerProfile,
                    maxConcurrency = maxConcurrency,
                    markPendingRestart = restartAppAfterSave
                )
                // Flush Room WAL safely to disk before restart so 0 bytes of tasks/memory/skills are lost
                runCatching {
                    container.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                }
            }
            val updatedSettings = settings.value.copy(
                ramBudgetMb = ramBudgetMb,
                storageQuotaMb = storageQuotaMb,
                adaptiveDeviceScaling = adaptiveDeviceScaling,
                lowResourceMode = lowResourceMode,
                powerProfile = powerProfile,
                maxConcurrency = maxConcurrency
            )
            container.resourceManager.refresh(updatedSettings)
            if (restartAppAfterSave) {
                triggerSafeAppRestart()
            } else {
                showBanner("Resource budget updated (RAM ${ramBudgetMb} MB • ROM ${storageQuotaMb} MB).")
            }
        }
    }

    private suspend fun triggerSafeAppRestart() {
        val app = getApplication<Application>()
        val launchIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        kotlinx.coroutines.delay(140)
        if (launchIntent != null) {
            app.startActivity(launchIntent)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    fun clearStorageDirectory(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.resourceManager.clearDirectorySafely(name)
            container.resourceManager.refresh(settings.value)
            showBanner("Cleared $name safely.")
        }
    }

    fun fetchGeminiModels(preferredSlot: SecretSlot? = null) {
        viewModelScope.launch {
            val key = preferredSlot?.let { container.keyStore.getSecret(it) }?.takeIf { it.isNotBlank() }
                ?: container.keyStore.getConfiguredGeminiKeys().firstOrNull()?.second
            if (key.isNullOrBlank()) {
                showBanner("Add at least one Gemini API key (#1-#5) first to discover live models.")
                return@launch
            }
            _isFetchingModels.value = true
            container.geminiProvider.fetchAvailableModels(key, forceRefresh = true).fold(
                onSuccess = { list ->
                    _geminiModels.value = list
                    showBanner("Discovered ${list.size} live Gemini models across your active API pool.")
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
                    val isImg = com.example.runtime.MediaVisionHelper.isImageAttachment(name, mime)
                    val excerpt = if (!isImg && bytes.isNotEmpty() && (
                            com.example.runtime.DocumentTextExtractor.isStructuredDocument(name, mime) ||
                                com.example.runtime.DocumentTextExtractor.isPlainTextDocument(name, mime)
                            )
                    ) {
                        com.example.runtime.DocumentTextExtractor.extractText(
                            fileName = name,
                            bytes = bytes,
                            mimeType = mime,
                            maxChars = 6000
                        ).takeIf { it.isNotBlank() }
                    } else null

                    val visionImages = when {
                        isImg -> listOfNotNull(
                            com.example.runtime.MediaVisionHelper.encodeImageBytesToBase64(bytes, name)
                        )
                        name.lowercase().endsWith(".pdf") && (excerpt == null || excerpt.length < 50) -> {
                            com.example.runtime.MediaVisionHelper.renderPdfPagesToImages(savedFile, name, maxPages = 3)
                        }
                        else -> emptyList()
                    }

                    AgentUploadedAttachment(
                        displayName = name,
                        relativePath = relPath,
                        mimeType = mime,
                        sizeBytes = bytes.size.toLong(),
                        textExcerpt = excerpt,
                        imageAttachments = visionImages
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

    // Universal Auto-Skill Importer Actions (Single, Batch URL, Full Repo ZIP & 1,000+ Catalogs)
    fun importSkillFromUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var totalImported = 0
            val sampleNames = mutableListOf<String>()
            var lastError: String? = null
            _skillImportProgress.value = "ফাইল/ZIP পড়া হচ্ছে..."
            try {
                for ((idx, uri) in uris.withIndex()) {
                    runCatching {
                        val resolver = context.contentResolver
                        val name = queryDisplayName(context, uri) ?: "skill_${System.currentTimeMillis()}.md"
                        _skillImportProgress.value = "লোড হচ্ছে (${idx + 1}/${uris.size}): $name..."
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                        require(bytes.isNotEmpty()) { "$name ফাইলটি খালি।" }
                        require(bytes.size <= 60 * 1024 * 1024) { "$name ৬০ MB এর বেশি।" }
                        val savedFile = container.workspaceManager.saveUploadedFile(name, bytes).getOrThrow()
                        val relPath = container.workspaceManager.relativePathOf(savedFile)
                        container.universalSkillManager.importFromUploadedPathBulk(
                            relativePath = relPath,
                            displayName = name,
                            onProgress = { status -> _skillImportProgress.value = status }
                        ).getOrThrow()
                    }.onSuccess { bulk ->
                        totalImported += bulk.importedCount
                        if (sampleNames.size < 6) sampleNames.addAll(bulk.sampleTitles.take(6 - sampleNames.size))
                    }.onFailure { err ->
                        lastError = err.message
                    }
                }
                refreshFiles()
                if (totalImported > 0) {
                    val preview = sampleNames.joinToString(", ")
                    showBanner("${totalImported}টি অটো-স্কিল যুক্ত হয়েছে" + if (preview.isNotBlank()) " ($preview)" else "")
                } else if (lastError != null) {
                    showBanner("স্কিল ইমপোর্ট ব্যর্থ: $lastError")
                }
            } finally {
                _skillImportProgress.value = null
            }
        }
    }

    fun importSkillFromSource(source: String, titleHint: String = "") {
        val trimmed = source.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _skillImportProgress.value = "সোর্স বিশ্লেষণ ও ডাউনলোড হচ্ছে..."
            try {
                container.universalSkillManager.importAutoBulk(
                    source = trimmed,
                    titleHint = titleHint,
                    onProgress = { status -> _skillImportProgress.value = status }
                ).fold(
                    onSuccess = { bulk ->
                        refreshFiles()
                        if (bulk.importedCount == 1 && bulk.firstSkill != null) {
                            val sk = bulk.firstSkill
                            showBanner("অটো-কনফিগার সম্পন্ন: ${sk.title} (${sk.ecosystem} • ${sk.wordCount} words)")
                        } else {
                            val preview = bulk.sampleTitles.take(4).joinToString(", ")
                            showBanner("${bulk.importedCount}টি স্কিল অটো-পুশ সম্পন্ন [${bulk.modeLabel}] • $preview")
                        }
                    },
                    onFailure = { err ->
                        showBanner("স্কিল ইমপোর্ট ব্যর্থ: ${err.message}")
                    }
                )
            } finally {
                _skillImportProgress.value = null
            }
        }
    }

    fun absorbPendingAttachmentsAsSkills() {
        val current = _pendingAttachments.value
        if (current.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val absorbedIds = mutableSetOf<String>()
            var totalImported = 0
            val importedTitles = mutableListOf<String>()
            var lastError: String? = null
            _skillImportProgress.value = "সংযুক্ত ফাইল থেকে স্কিল যুক্ত হচ্ছে..."
            try {
                for (att in current) {
                    container.universalSkillManager.importFromUploadedPathBulk(
                        relativePath = att.relativePath,
                        displayName = att.displayName,
                        onProgress = { status -> _skillImportProgress.value = status }
                    ).onSuccess { bulk ->
                        absorbedIds.add(att.id)
                        totalImported += bulk.importedCount
                        if (importedTitles.size < 6) importedTitles.addAll(bulk.sampleTitles.take(6 - importedTitles.size))
                    }.onFailure { err ->
                        lastError = err.message
                    }
                }
                if (absorbedIds.isNotEmpty()) {
                    _pendingAttachments.update { list -> list.filterNot { it.id in absorbedIds } }
                    refreshFiles()
                    showBanner("${totalImported}টি অটো-স্কিল যুক্ত হয়েছে: ${importedTitles.joinToString(", ")}")
                } else if (lastError != null) {
                    showBanner("স্কিল অ্যাবজর্ব ব্যর্থ: $lastError")
                }
            } finally {
                _skillImportProgress.value = null
            }
        }
    }

    fun toggleImportedSkill(slug: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.universalSkillManager.setSkillEnabled(slug, enabled)
        }
    }

    fun deleteImportedSkill(slug: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.universalSkillManager.deleteSkill(slug).fold(
                onSuccess = {
                    refreshFiles()
                    showBanner("স্কিল মুছে ফেলা হয়েছে ($slug)")
                },
                onFailure = { showBanner("মুছে ফেলতে ব্যর্থ: ${it.message}") }
            )
        }
    }

    fun clearAllImportedSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            container.universalSkillManager.clearAllSkills().fold(
                onSuccess = {
                    refreshFiles()
                    showBanner("সব ইমপোর্টেড স্কিল মুছে ফেলা হয়েছে।")
                },
                onFailure = { showBanner("সব মুছে ফেলতে ব্যর্থ: ${it.message}") }
            )
        }
    }

    fun searchImportedSkillsLocal(
        query: String,
        allSkills: List<ImportedSkillEntity>
    ): List<ImportedSkillEntity> {
        val clean = query.trim()
        if (clean.isBlank()) return allSkills.take(80)
        return container.universalSkillManager
            .matchRelevantSkills(clean, allSkills, limit = 60)
            .map { it.skill }
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
                    val shellRes = container.linuxEnv.executeCommand("sh $path", confirmedLevel2 = false)
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

    // WhatsApp AI Bot Management Actions
    fun refreshWhatsAppStatus() {
        container.whatsAppBotEngine.refreshNotificationStatus()
    }

    fun toggleWhatsAppBotMaster(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.updateWhatsAppBotConfig(enabled = enabled)
            showBanner(if (enabled) "WhatsApp AI Bot চালু হয়েছে।" else "WhatsApp AI Bot বন্ধ করা হয়েছে।")
        }
    }

    fun updateWhatsAppGlobalRules(
        defaultAutoReplyDm: Boolean,
        defaultAutoReplyGroup: Boolean,
        globalAntiLink: Boolean,
        globalAntiSticker: Boolean,
        globalAntiForward: Boolean,
        persona: String
    ) {
        viewModelScope.launch {
            container.settingsStore.updateWhatsAppBotConfig(
                defaultAutoReplyDm = defaultAutoReplyDm,
                defaultAutoReplyGroup = defaultAutoReplyGroup,
                globalAntiLink = globalAntiLink,
                globalAntiSticker = globalAntiSticker,
                globalAntiForward = globalAntiForward,
                persona = persona
            )
            showBanner("WhatsApp Bot গ্লোবাল সেটিংস ও AI পার্সোনা সেভ হয়েছে।")
        }
    }

    fun requestWhatsAppPhoneLinkCode(phone: String) {
        viewModelScope.launch {
            container.whatsAppWebBridge.triggerPhoneLinkCode(phone).fold(
                onSuccess = { codeOrHint ->
                    showBanner("WhatsApp Link Code: $codeOrHint")
                },
                onFailure = { err ->
                    showBanner("Link Code error: ${err.message}")
                }
            )
        }
    }

    fun syncWhatsAppChats() {
        viewModelScope.launch {
            container.whatsAppWebBridge.syncChatListNow().fold(
                onSuccess = { count -> showBanner("Synced $count WhatsApp chats/groups.") },
                onFailure = { err -> showBanner("Sync error: ${err.message}") }
            )
        }
    }

    fun reloadWhatsAppWeb(resetSession: Boolean = false) {
        if (resetSession) container.whatsAppWebBridge.clearSessionAndRelink()
        else container.whatsAppWebBridge.reloadSession()
    }

    fun openWhatsAppNotificationSettings() {
        WhatsAppNotificationService.openNotificationListenerSettings(getApplication()).onFailure {
            showBanner(it.message ?: "Unable to open Notification Access settings")
        }
    }

    fun sendWhatsAppMessage(
        targetChatName: String,
        isGroup: Boolean,
        messageOrPrompt: String,
        composeWithAi: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            container.whatsAppBotEngine.sendManualOrAiMessage(
                targetChatName = targetChatName,
                isGroup = isGroup,
                messageOrPrompt = messageOrPrompt,
                composeWithAi = composeWithAi
            ).fold(
                onSuccess = { summary -> showBanner(summary) },
                onFailure = { err -> showBanner("Send failed: ${err.message}") }
            )
        }
    }

    fun updateWhatsAppChatRules(
        chatName: String,
        isGroup: Boolean,
        autoReply: Boolean? = null,
        antiLink: Boolean? = null,
        antiSticker: Boolean? = null,
        antiForward: Boolean? = null,
        commandsEnabled: Boolean? = null,
        customTopicPrompt: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = container.whatsAppBotEngine.configureChatRules(
                chatName = chatName,
                isGroup = isGroup,
                autoReply = autoReply,
                antiLink = antiLink,
                antiSticker = antiSticker,
                antiForward = antiForward,
                commandsEnabled = commandsEnabled,
                customTopicPrompt = customTopicPrompt
            )
            showBanner("${updated.displayName} আপডেট হয়েছে (AutoReply=${updated.autoReplyEnabled}, AntiLink=${updated.antiLinkEnabled}, AntiSticker=${updated.antiStickerEnabled})")
        }
    }

    fun deleteWhatsAppChatRecord(chatKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.repository.deleteWhatsAppChat(chatKey)
        }
    }

    fun observeWhatsAppChatMessages(chatKey: String) =
        container.whatsAppBotEngine.observeMessagesForChat(chatKey)
}
