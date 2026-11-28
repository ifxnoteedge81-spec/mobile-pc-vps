package com.example

import android.app.Application
import android.content.Context
import com.example.accessibility.DeviceAutomationController
import com.example.agent.AutonomousAgentEngine
import com.example.agent.ToolRegistry
import com.example.agent.UniversalSkillManager
import com.example.ai.AiModelRouter
import com.example.ai.GeminiProvider
import com.example.ai.OpenRouterProvider
import com.example.browser.BrowserController
import com.example.resources.DeviceResourceManager
import com.example.runtime.CadDrawingEngine
import com.example.runtime.LocalJsEngine
import com.example.runtime.LocalLinuxEnvironment
import com.example.runtime.LocalPythonEngine
import com.example.runtime.LocalWebServer
import com.example.runtime.VirtualWorkspaceManager
import com.example.scheduler.AgentWorkScheduler
import com.example.security.NetworkAuditLogger
import com.example.security.SecureKeyStore
import com.example.storage.AgentRepository
import com.example.storage.AppDatabase
import com.example.storage.SettingsDataStore
import com.example.voice.SpeakerVoiceprintManager
import com.example.voice.VoiceAssistantController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("PersonalAiApp", "Uncaught background coroutine error", throwable)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + appExceptionHandler)

    val keyStore = SecureKeyStore(context)
    val auditLogger = NetworkAuditLogger()
    val settingsStore = SettingsDataStore(context)
    val database = AppDatabase.getInstance(context)
    val repository = AgentRepository(database)

    val workspaceManager = VirtualWorkspaceManager(context)
    val resourceManager = DeviceResourceManager(context, workspaceManager)
    val pythonEngine = LocalPythonEngine(workspaceManager)
    val jsEngine = LocalJsEngine(context)
    val webServer = LocalWebServer(workspaceManager, appScope)
    val linuxEnv = LocalLinuxEnvironment(workspaceManager, pythonEngine, jsEngine, webServer)
    val cadEngine = CadDrawingEngine(workspaceManager)

    val browserController = BrowserController(workspaceManager, auditLogger)
    val deviceController = DeviceAutomationController(context)
    val workScheduler = AgentWorkScheduler(context)
    val universalSkillManager = UniversalSkillManager(workspaceManager, repository, auditLogger)
    val zeroLatencyBots = com.example.agent.ZeroLatencyBotEngine(
        deviceController = deviceController,
        browserController = browserController,
        skillManager = universalSkillManager,
        repository = repository,
        workspace = workspaceManager
    )

    val geminiProvider = GeminiProvider(auditLogger)
    val openRouterProvider = OpenRouterProvider(auditLogger)
    val aiRouter = AiModelRouter(context, keyStore, geminiProvider, openRouterProvider, repository)
    val updateChecker = com.example.runtime.AppUpdateChecker(context, auditLogger)

    val miniAiBrain = com.example.ai.LocalMiniAiBrain(
        java.io.File(context.filesDir, "mini_ai_brain.json")
    )
    val whatsAppWebBridge = com.example.whatsapp.WhatsAppWebBridge(context, appScope, auditLogger)
    val whatsAppBotEngine = com.example.whatsapp.WhatsAppBotEngine(
        context = context,
        scope = appScope,
        repository = repository,
        settingsStore = settingsStore,
        aiRouter = aiRouter,
        webBridge = whatsAppWebBridge,
        miniAiBrain = miniAiBrain
    )

    val toolRegistry = ToolRegistry(
        workspace = workspaceManager,
        linuxEnv = linuxEnv,
        pythonEngine = pythonEngine,
        jsEngine = jsEngine,
        webServer = webServer,
        cadEngine = cadEngine,
        resourceManager = resourceManager,
        browser = browserController,
        deviceController = deviceController,
        repository = repository,
        skillManager = universalSkillManager,
        botEngine = zeroLatencyBots,
        whatsAppBotEngine = whatsAppBotEngine,
        workScheduler = workScheduler
    )

    val agentEngine = AutonomousAgentEngine(
        scope = appScope,
        router = aiRouter,
        toolRegistry = toolRegistry,
        repository = repository,
        linuxEnv = linuxEnv,
        resourceManager = resourceManager,
        skillManager = universalSkillManager,
        settingsFlow = settingsStore.settings,
        botEngine = zeroLatencyBots,
        miniAiBrain = miniAiBrain,
        onInterventionAlert = { req ->
            com.example.scheduler.AgentForegroundService.postInterventionNotification(
                context = context,
                challengeType = req.challengeType,
                reason = req.reason
            )
        }
    )

    val speakerVoiceprintManager = SpeakerVoiceprintManager(context)
    val voiceAssistantController = VoiceAssistantController(
        context = context,
        scope = appScope,
        settingsStore = settingsStore,
        voiceprintManager = speakerVoiceprintManager,
        onExecuteVoiceCommand = { spokenCmd ->
            val (_, report) = agentEngine.executeCommandAndWaitWithReport(spokenCmd)
            report
        }
    )

    init {
        // Connect WhatsApp Owner `.cmd` tool directly to the Autonomous AI Computer engine
        whatsAppBotEngine.attachComputerTaskBridge { taskPrompt ->
            val (_, report) = agentEngine.executeCommandAndWaitWithReport(taskPrompt)
            report.take(900)
        }

        // Sweep overdue WhatsApp scheduled items & restore pending WorkManager triggers on startup
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val pending = repository.getPendingWhatsAppSchedulesSnapshot()
                workScheduler.scheduleAllPendingWhatsApp(pending)
                whatsAppBotEngine.sweepDueScheduledDispatches()
            }
        }
    }
}

class PersonalAiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.example.runtime.CrashDiagnosticRecorder.install(this)
        container = AppContainer(this)
    }
}
