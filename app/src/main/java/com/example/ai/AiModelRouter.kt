package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.security.SecureKeyStore
import com.example.security.SecureKeyStore.SecretSlot
import com.example.storage.AgentRepository
import com.example.storage.AiProviderType
import com.example.storage.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AiModelRouter(
    private val context: Context,
    private val keyStore: SecureKeyStore,
    private val geminiProvider: GeminiProvider,
    private val openRouterProvider: OpenRouterProvider,
    private val repository: AgentRepository
) {
    private val _lastResolvedModel = MutableStateFlow<String?>(null)
    val lastResolvedModel: StateFlow<String?> = _lastResolvedModel.asStateFlow()

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getActiveLabel(settings: AppSettings): String {
        val hasGemini = keyStore.hasSecret(SecretSlot.GEMINI_API_KEY)
        val hasOpenRouter = keyStore.hasSecret(SecretSlot.OPENROUTER_API_KEY)
        val geminiLabel = if (settings.geminiModel == AUTO_MODEL_ID || settings.geminiModel.isBlank()) {
            "Auto (API)"
        } else settings.geminiModel
        val openRouterLabel = if (settings.openRouterModel == AUTO_MODEL_ID || settings.openRouterModel.isBlank()) {
            "Auto (API)"
        } else settings.openRouterModel
        val liveSuffix = _lastResolvedModel.value?.let { " [$it]" }.orEmpty()

        return when (settings.preferredProvider) {
            AiProviderType.GEMINI -> if (hasGemini) "Gemini • $geminiLabel$liveSuffix" else "Gemini (Key missing)"
            AiProviderType.OPENROUTER -> if (hasOpenRouter) "OpenRouter • $openRouterLabel$liveSuffix" else "OpenRouter (Key missing)"
            AiProviderType.AUTO -> when {
                hasGemini && hasOpenRouter -> "Auto Router • Gemini/OR$liveSuffix"
                hasGemini -> "Auto -> Gemini ($geminiLabel)$liveSuffix"
                hasOpenRouter -> "Auto -> OpenRouter ($openRouterLabel)$liveSuffix"
                else -> "Offline / No API Key Configured"
            }
        }
    }

    suspend fun complete(
        settings: AppSettings,
        complexity: TaskComplexity,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        onStreamChunk: (String) -> Unit = {}
    ): Result<AiCompletionResult> {
        if (!isOnline()) {
            return Result.failure(
                IllegalStateException("Device is offline. Local terminal, scripts, files, and task history remain available, but cloud AI requires an internet connection.")
            )
        }

        val geminiKey = keyStore.getSecret(SecretSlot.GEMINI_API_KEY)
        val openRouterKey = keyStore.getSecret(SecretSlot.OPENROUTER_API_KEY)

        if (geminiKey.isNullOrBlank() && openRouterKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("No AI API key configured. Open AI Model Settings to add your Google Gemini or OpenRouter API key.")
            )
        }

        val routeOrder = buildRouteOrder(settings, complexity, !geminiKey.isNullOrBlank(), !openRouterKey.isNullOrBlank())
        var lastFailure: Throwable? = null

        for (provider in routeOrder) {
            val res = when (provider) {
                AiProviderType.GEMINI -> runGeminiWithAutoDiscovery(
                    apiKey = geminiKey!!,
                    configuredModel = settings.geminiModel,
                    complexity = complexity,
                    preferFast = settings.preferFreeModelsOnSimpleTasks,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    tools = tools,
                    stream = settings.streamingEnabled,
                    onStreamChunk = onStreamChunk
                )
                AiProviderType.OPENROUTER -> runOpenRouterWithAutoDiscovery(
                    apiKey = openRouterKey!!,
                    configuredModel = settings.openRouterModel,
                    complexity = complexity,
                    preferFree = settings.preferFreeModelsOnSimpleTasks,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    tools = tools,
                    stream = settings.streamingEnabled,
                    onStreamChunk = onStreamChunk
                )
                AiProviderType.AUTO -> continue
            }

            if (res.isSuccess) {
                val completion = res.getOrThrow()
                _lastResolvedModel.value = completion.model
                repository.recordUsage(
                    provider = completion.provider,
                    modelId = completion.model,
                    promptTokens = completion.promptTokens,
                    completionTokens = completion.completionTokens
                )
                return res
            } else {
                lastFailure = res.exceptionOrNull()
                if (!settings.autoFallbackEnabled) break
            }
        }

        return Result.failure(lastFailure ?: IllegalStateException("All configured AI providers failed."))
    }

    private suspend fun runGeminiWithAutoDiscovery(
        apiKey: String,
        configuredModel: String,
        complexity: TaskComplexity,
        preferFast: Boolean,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        stream: Boolean,
        onStreamChunk: (String) -> Unit
    ): Result<AiCompletionResult> {
        val candidates = geminiProvider.resolveCandidateModels(
            apiKey = apiKey,
            requestedModel = configuredModel,
            complexity = complexity,
            preferFastOnSimple = preferFast
        )
        if (candidates.isEmpty()) {
            return Result.failure(
                IllegalStateException("Gemini API did not return any available generateContent models for this API key.")
            )
        }

        var lastError: Throwable? = null
        for (modelId in candidates.take(4)) {
            val res = geminiProvider.generate(
                apiKey = apiKey,
                model = modelId,
                systemPrompt = systemPrompt,
                messages = messages,
                tools = tools,
                stream = stream,
                onStreamChunk = onStreamChunk
            )
            if (res.isSuccess) return res
            lastError = res.exceptionOrNull()
            val errMsg = lastError?.message.orEmpty()
            // Only fall back to next model if model-level failure (404/429/403/400 unsupported), not invalid API key
            if (errMsg.contains("API key not valid", ignoreCase = true) || errMsg.contains("(401)")) {
                break
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Gemini API call failed across discovered models."))
    }

    private suspend fun runOpenRouterWithAutoDiscovery(
        apiKey: String,
        configuredModel: String,
        complexity: TaskComplexity,
        preferFree: Boolean,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        stream: Boolean,
        onStreamChunk: (String) -> Unit
    ): Result<AiCompletionResult> {
        val candidates = openRouterProvider.resolveCandidateModels(
            apiKey = apiKey,
            requestedModel = configuredModel,
            complexity = complexity,
            requiresTools = tools.isNotEmpty(),
            preferFreeOnSimple = preferFree
        )
        var lastError: Throwable? = null
        for (modelId in candidates.take(4)) {
            val res = openRouterProvider.generate(
                apiKey = apiKey,
                model = modelId,
                systemPrompt = systemPrompt,
                messages = messages,
                tools = tools,
                stream = stream,
                onStreamChunk = onStreamChunk
            )
            if (res.isSuccess) return res
            lastError = res.exceptionOrNull()
            val errMsg = lastError?.message.orEmpty()
            if (errMsg.contains("(401)") || errMsg.contains("invalid auth", ignoreCase = true)) {
                break
            }
        }
        return Result.failure(lastError ?: IllegalStateException("OpenRouter API call failed across discovered models."))
    }

    private fun buildRouteOrder(
        settings: AppSettings,
        complexity: TaskComplexity,
        hasGemini: Boolean,
        hasOpenRouter: Boolean
    ): List<AiProviderType> {
        val list = mutableListOf<AiProviderType>()
        when (settings.preferredProvider) {
            AiProviderType.GEMINI -> {
                if (hasGemini) list.add(AiProviderType.GEMINI)
                if (settings.autoFallbackEnabled && hasOpenRouter) list.add(AiProviderType.OPENROUTER)
            }
            AiProviderType.OPENROUTER -> {
                if (hasOpenRouter) list.add(AiProviderType.OPENROUTER)
                if (settings.autoFallbackEnabled && hasGemini) list.add(AiProviderType.GEMINI)
            }
            AiProviderType.AUTO -> {
                if (complexity == TaskComplexity.SIMPLE && settings.preferFreeModelsOnSimpleTasks && hasOpenRouter) {
                    list.add(AiProviderType.OPENROUTER)
                    if (hasGemini) list.add(AiProviderType.GEMINI)
                } else {
                    if (hasGemini) list.add(AiProviderType.GEMINI)
                    if (hasOpenRouter) list.add(AiProviderType.OPENROUTER)
                }
            }
        }
        return list
    }
}
