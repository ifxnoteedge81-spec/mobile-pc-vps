package com.example

import android.app.Application
import android.content.Context
import com.example.accessibility.DeviceAutomationController
import com.example.agent.AutonomousAgentEngine
import com.example.agent.ToolRegistry
import com.example.ai.AiModelRouter
import com.example.ai.GeminiProvider
import com.example.ai.OpenRouterProvider
import com.example.browser.BrowserController
import com.example.resources.DeviceResourceManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    val browserController = BrowserController(workspaceManager, auditLogger)
    val deviceController = DeviceAutomationController(context)
    val workScheduler = AgentWorkScheduler(context)

    val geminiProvider = GeminiProvider(auditLogger)
    val openRouterProvider = OpenRouterProvider(auditLogger)
    val aiRouter = AiModelRouter(context, keyStore, geminiProvider, openRouterProvider, repository)

    val toolRegistry = ToolRegistry(
        workspace = workspaceManager,
        linuxEnv = linuxEnv,
        pythonEngine = pythonEngine,
        jsEngine = jsEngine,
        webServer = webServer,
        browser = browserController,
        deviceController = deviceController,
        repository = repository
    )

    val agentEngine = AutonomousAgentEngine(
        scope = appScope,
        router = aiRouter,
        toolRegistry = toolRegistry,
        repository = repository,
        linuxEnv = linuxEnv,
        resourceManager = resourceManager,
        settingsFlow = settingsStore.settings,
        onInterventionAlert = { req ->
            com.example.scheduler.AgentForegroundService.postInterventionNotification(
                context = context,
                challengeType = req.challengeType,
                reason = req.reason
            )
        }
    )
}

class PersonalAiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
