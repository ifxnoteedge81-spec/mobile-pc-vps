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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AiModelRouter(
    private val context: Context,
    private val keyStore: SecureKeyStore,
    private val geminiProvider: GeminiProvider,
    private val openRouterProvider: OpenRouterProvider,
    private val repository: AgentRepository
) {
    private val _lastResolvedModel = MutableStateFlow<String?>(null)
    val lastResolvedModel: StateFlow<String?> = _lastResolvedModel.asStateFlow()

    private val _lastActiveKeySlot = MutableStateFlow<String?>(null)
    val lastActiveKeySlot: StateFlow<String?> = _lastActiveKeySlot.asStateFlow()

    private val geminiRotationCursor = AtomicInteger(0)
    private val keyCooldownUntil = ConcurrentHashMap<SecretSlot, Long>()

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getActiveLabel(settings: AppSettings): String {
        val geminiCount = keyStore.getConfiguredGeminiKeys().size
        val hasGemini = geminiCount > 0
        val hasOpenRouter = keyStore.hasSecret(SecretSlot.OPENROUTER_API_KEY)
        val geminiLabel = if (settings.geminiModel == AUTO_MODEL_ID || settings.geminiModel.isBlank()) {
            "Auto"
        } else settings.geminiModel
        val openRouterLabel = if (settings.openRouterModel == AUTO_MODEL_ID || settings.openRouterModel.isBlank()) {
            "Auto"
        } else settings.openRouterModel
        val slotTag = _lastActiveKeySlot.value?.let { "$it • " }.orEmpty()
        val liveModel = _lastResolvedModel.value?.let { " [$slotTag$it]" }.orEmpty()

        return when (settings.preferredProvider) {
            AiProviderType.GEMINI -> if (hasGemini) {
                "Gemini ($geminiCount/5 Keys${if (hasOpenRouter) "+OR" else ""}) • $geminiLabel$liveModel"
            } else {
                "Gemini (Key missing)"
            }
            AiProviderType.OPENROUTER -> if (hasOpenRouter) {
                "OpenRouter • $openRouterLabel$liveModel"
            } else {
                "OpenRouter (Key missing)"
            }
            AiProviderType.AUTO -> when {
                hasGemini && hasOpenRouter -> "Auto Pool ($geminiCount/5 Gemini -> OR)$liveModel"
                hasGemini -> "Auto Gemini ($geminiCount/5 Keys) • $geminiLabel$liveModel"
                hasOpenRouter -> "Auto OpenRouter • $openRouterLabel$liveModel"
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
                IllegalStateException("Device is offline. Local terminal, scripts, files, and task history remain available, but cloud AI requires internet.")
            )
        }

        val configuredGeminiKeys = keyStore.getConfiguredGeminiKeys()
        val openRouterKey = keyStore.getSecret(SecretSlot.OPENROUTER_API_KEY)

        if (configuredGeminiKeys.isEmpty() && openRouterKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("No AI API key configured. Open AI Model Settings to add your Google Gemini keys (1-5) or OpenRouter key.")
            )
        }

        val routeOrder = buildRouteOrder(
            settings = settings,
            hasGemini = configuredGeminiKeys.isNotEmpty(),
            hasOpenRouter = !openRouterKey.isNullOrBlank()
        )
        var lastFailure: Throwable? = null

        for (provider in routeOrder) {
            val res = when (provider) {
                AiProviderType.GEMINI -> runGeminiKeyPoolRotation(
                    keys = configuredGeminiKeys,
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
                _lastActiveKeySlot.value = completion.keySlotLabel ?: completion.provider
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

        return Result.failure(lastFailure ?: IllegalStateException("All configured Gemini keys (1-5) and OpenRouter failed."))
    }

    private suspend fun runGeminiKeyPoolRotation(
        keys: List<Pair<SecretSlot, String>>,
        configuredModel: String,
        complexity: TaskComplexity,
        preferFast: Boolean,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        stream: Boolean,
        onStreamChunk: (String) -> Unit
    ): Result<AiCompletionResult> {
        if (keys.isEmpty()) {
            return Result.failure(IllegalStateException("No Gemini keys configured."))
        }

        val now = System.currentTimeMillis()
        val startOffset = Math.floorMod(geminiRotationCursor.getAndIncrement(), keys.size)
        val rotatedKeys = List(keys.size) { i -> keys[(startOffset + i) % keys.size] }

        // Try healthy (non-cooled-down) keys first, then fall back to cooled-down keys if all are cooling down
        val orderedKeys = rotatedKeys.sortedBy { (slot, _) ->
            val until = keyCooldownUntil[slot] ?: 0L
            if (until > now) 1 else 0
        }

        var lastError: Throwable? = null

        for ((slot, apiKey) in orderedKeys) {
            val candidates = geminiProvider.resolveCandidateModels(
                apiKey = apiKey,
                requestedModel = configuredModel,
                complexity = complexity,
                preferFastOnSimple = preferFast
            )
            if (candidates.isEmpty()) {
                lastError = IllegalStateException("${slot.label}: No generateContent models available.")
                keyCooldownUntil[slot] = System.currentTimeMillis() + 120_000L
                continue
            }

            // Try top 2 fast/capable models per key so key rotation is super quick
            var keyQuotaOrAuthFailed = false
            for (modelId in candidates.take(2)) {
                val res = geminiProvider.generate(
                    apiKey = apiKey,
                    model = modelId,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    tools = tools,
                    stream = stream,
                    onStreamChunk = onStreamChunk
                )
                if (res.isSuccess) {
                    keyCooldownUntil.remove(slot)
                    val slotNum = SecureKeyStore.geminiSlots.indexOf(slot) + 1
                    return Result.success(res.getOrThrow().copy(keySlotLabel = "Key #$slotNum"))
                }

                lastError = res.exceptionOrNull()
                val errMsg = lastError?.message.orEmpty()
                val isRateOrQuota = errMsg.contains("(429)") ||
                    errMsg.contains("quota", ignoreCase = true) ||
                    errMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
                val isAuthOrPerm = errMsg.contains("API key not valid", ignoreCase = true) ||
                    errMsg.contains("(401)") ||
                    errMsg.contains("(403)")

                if (isRateOrQuota) {
                    keyCooldownUntil[slot] = System.currentTimeMillis() + 90_000L
                    keyQuotaOrAuthFailed = true
                    break // Immediately rotate to next Gmail API key
                }
                if (isAuthOrPerm) {
                    keyCooldownUntil[slot] = System.currentTimeMillis() + 300_000L
                    keyQuotaOrAuthFailed = true
                    break // Immediately rotate to next Gmail API key
                }
            }
            if (!keyQuotaOrAuthFailed && lastError != null) {
                keyCooldownUntil[slot] = System.currentTimeMillis() + 30_000L
            }
        }

        return Result.failure(lastError ?: IllegalStateException("All ${keys.size} Gemini API key(s) failed or hit quota."))
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
        for (modelId in candidates.take(3)) {
            val res = openRouterProvider.generate(
                apiKey = apiKey,
                model = modelId,
                systemPrompt = systemPrompt,
                messages = messages,
                tools = tools,
                stream = stream,
                onStreamChunk = onStreamChunk
            )
            if (res.isSuccess) {
                return Result.success(res.getOrThrow().copy(keySlotLabel = "OpenRouter"))
            }
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
                // Prioritize the 5-key Gemini rotation pool first, then automatically fall back to OpenRouter
                if (hasGemini) list.add(AiProviderType.GEMINI)
                if (hasOpenRouter) list.add(AiProviderType.OPENROUTER)
            }
        }
        return list
    }
}
