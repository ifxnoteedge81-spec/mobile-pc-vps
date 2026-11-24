package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.security.SecureKeyStore
import com.example.security.SecureKeyStore.SecretSlot
import com.example.storage.AgentRepository
import com.example.storage.AiProviderType
import com.example.storage.AppSettings

class AiModelRouter(
    private val context: Context,
    private val keyStore: SecureKeyStore,
    private val geminiProvider: GeminiProvider,
    private val openRouterProvider: OpenRouterProvider,
    private val repository: AgentRepository
) {

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getActiveLabel(settings: AppSettings): String {
        val hasGemini = keyStore.hasSecret(SecretSlot.GEMINI_API_KEY)
        val hasOpenRouter = keyStore.hasSecret(SecretSlot.OPENROUTER_API_KEY)
        return when (settings.preferredProvider) {
            AiProviderType.GEMINI -> if (hasGemini) "Gemini • ${settings.geminiModel}" else "Gemini (Key missing)"
            AiProviderType.OPENROUTER -> if (hasOpenRouter) "OpenRouter • ${settings.openRouterModel}" else "OpenRouter (Key missing)"
            AiProviderType.AUTO -> when {
                hasGemini -> "Auto -> Gemini (${settings.geminiModel})"
                hasOpenRouter -> "Auto -> OpenRouter (${settings.openRouterModel})"
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

        val chosenGeminiModel = when (complexity) {
            TaskComplexity.REASONING, TaskComplexity.MULTISTEP_AGENT -> settings.geminiModel
            TaskComplexity.CODING -> settings.geminiModel
            TaskComplexity.SIMPLE -> if (settings.preferFreeModelsOnSimpleTasks) "gemini-2.0-flash" else settings.geminiModel
        }

        val routeOrder = buildRouteOrder(settings, complexity, !geminiKey.isNullOrBlank(), !openRouterKey.isNullOrBlank())
        var lastFailure: Throwable? = null

        for (provider in routeOrder) {
            val res = when (provider) {
                AiProviderType.GEMINI -> geminiProvider.generate(
                    apiKey = geminiKey!!,
                    model = chosenGeminiModel,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    tools = tools,
                    stream = settings.streamingEnabled,
                    onStreamChunk = onStreamChunk
                )
                AiProviderType.OPENROUTER -> openRouterProvider.generate(
                    apiKey = openRouterKey!!,
                    model = settings.openRouterModel,
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
                if (complexity == TaskComplexity.SIMPLE && settings.preferFreeModelsOnSimpleTasks && settings.openRouterModel.endsWith(":free") && hasOpenRouter) {
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
