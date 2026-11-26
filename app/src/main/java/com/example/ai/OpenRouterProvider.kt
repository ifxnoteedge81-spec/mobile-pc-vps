package com.example.ai

import com.example.security.NetworkAuditLogger
import com.example.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OpenRouterProvider(
    private val auditLogger: NetworkAuditLogger
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedModelsFetchedAt: Long = 0L
    @Volatile
    private var cachedModels: List<OpenRouterModelInfo> = emptyList()
    private val freeTierCache = ConcurrentHashMap<Int, Pair<Boolean, Long>>()
    private val modelCooldowns = ConcurrentHashMap<String, Long>()

    fun clearCache() {
        cachedModelsFetchedAt = 0L
        cachedModels = emptyList()
        freeTierCache.clear()
        modelCooldowns.clear()
    }

    fun markFreeTierOnly(apiKey: String) {
        val cleanKey = SecureKeyStore.sanitizeSecret(apiKey)
        if (cleanKey.isNotBlank()) {
            freeTierCache[cleanKey.hashCode()] = true to System.currentTimeMillis()
        }
    }

    suspend fun isKeyFreeTierOnly(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val cleanKey = SecureKeyStore.sanitizeSecret(apiKey)
        if (cleanKey.isBlank()) return@withContext false
        val keyHash = cleanKey.hashCode()
        val cached = freeTierCache[keyHash]
        if (cached != null && System.currentTimeMillis() - cached.second < 10 * 60_000L) {
            return@withContext cached.first
        }
        val result = runCatching {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/auth/key")
                .addHeader("Authorization", "Bearer $cleanKey")
                .get()
                .build()
            discoveryClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use true
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONObject("data") ?: return@use true
                val isFreeTier = data.optBoolean("is_free_tier", true)
                val hasZeroRemaining = !data.isNull("limit_remaining") && data.optDouble("limit_remaining", 1.0) <= 0.0
                isFreeTier || hasZeroRemaining
            }
        }.getOrDefault(false)
        freeTierCache[keyHash] = result to System.currentTimeMillis()
        result
    }

    suspend fun fetchAvailableModels(apiKey: String?, forceRefresh: Boolean = false): Result<List<OpenRouterModelInfo>> =
        withContext(Dispatchers.IO) {
            val cleanKey = apiKey?.let { SecureKeyStore.sanitizeSecret(it) }?.takeIf { it.isNotBlank() }
            val fresh = System.currentTimeMillis() - cachedModelsFetchedAt < 30 * 60_000L
            if (!forceRefresh && fresh && cachedModels.isNotEmpty()) {
                return@withContext Result.success(cachedModels)
            }
            runCatching {
                val reqBuilder = Request.Builder()
                    .url("https://openrouter.ai/api/v1/models")
                    .get()
                if (cleanKey != null) {
                    reqBuilder.addHeader("Authorization", "Bearer $cleanKey")
                }
                discoveryClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "OpenRouter models HTTP ${resp.code}" }
                    auditLogger.log("openrouter.ai", "Model Discovery", "200 OK", raw.length.toLong())
                    val arr = JSONObject(raw).optJSONArray("data") ?: JSONArray()
                    val list = mutableListOf<OpenRouterModelInfo>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id")
                        if (id.isBlank()) continue
                        val name = obj.optString("name", id)
                        val ctx = obj.optInt("context_length", 8192)
                        val pricing = obj.optJSONObject("pricing")
                        val promptPrice = pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0
                        val compPrice = pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0
                        val isFree = id.endsWith(":free") || (promptPrice == 0.0 && compPrice == 0.0)
                        val supportedParams = obj.optJSONArray("supported_parameters")
                        val supportsTools = if (supportedParams != null) {
                            (0 until supportedParams.length()).any { supportedParams.optString(it) == "tools" }
                        } else false
                        list.add(OpenRouterModelInfo(id, name, ctx, isFree, supportsTools))
                    }
                    val sorted = list.sortedWith(
                        compareByDescending<OpenRouterModelInfo> { it.isFree }
                            .thenByDescending { it.supportsTools }
                            .thenByDescending { it.contextLength }
                            .thenBy { it.name }
                    )
                    cachedModelsFetchedAt = System.currentTimeMillis()
                    cachedModels = sorted
                    sorted
                }
            }
        }

    fun markModelFailure(modelId: String, cooldownMs: Long = 8 * 60_000L) {
        modelCooldowns[modelId] = System.currentTimeMillis() + cooldownMs
    }

    suspend fun resolveCandidateModels(
        apiKey: String,
        requestedModel: String,
        complexity: TaskComplexity,
        requiresTools: Boolean,
        preferFreeOnSimple: Boolean,
        forceFreeOnly: Boolean = false
    ): List<String> {
        if (requestedModel.isNotBlank() && requestedModel != AUTO_MODEL_ID) {
            return listOf(requestedModel)
        }
        val freeOnly = forceFreeOnly || isKeyFreeTierOnly(apiKey)
        val models = fetchAvailableModels(apiKey).getOrElse { emptyList() }
        if (models.isEmpty()) {
            return if (freeOnly) {
                listOf(
                    "openrouter/free",
                    "meta-llama/llama-3.3-70b-instruct:free",
                    "qwen/qwen-2.5-72b-instruct:free",
                    "google/gemma-3-27b-it:free",
                    "mistralai/mistral-small-3.1-24b-instruct:free"
                )
            } else {
                listOf("openrouter/auto", "openrouter/free")
            }
        }

        return filterAndRankModels(
            models = models,
            freeOnly = freeOnly,
            complexity = complexity,
            requiresTools = requiresTools,
            preferFreeOnSimple = preferFreeOnSimple
        )
    }

    internal fun filterAndRankModels(
        models: List<OpenRouterModelInfo>,
        freeOnly: Boolean,
        complexity: TaskComplexity,
        requiresTools: Boolean,
        preferFreeOnSimple: Boolean
    ): List<String> {
        val now = System.currentTimeMillis()
        val healthy = models.filter { m ->
            val lower = m.id.lowercase()
            val excluded = lower.contains("embed") || lower.contains("guard") || lower.contains("moderation")
            val cooledUntil = modelCooldowns[m.id] ?: 0L
            !excluded && now >= cooledUntil
        }.ifEmpty { models }

        // CRITICAL: When key is free-tier only, filter isFree FIRST so paid models (HTTP 402) are NEVER picked!
        val tierPool = if (freeOnly) {
            healthy.filter { it.isFree }.ifEmpty { models.filter { it.isFree } }
        } else if (preferFreeOnSimple && complexity == TaskComplexity.SIMPLE) {
            healthy.filter { it.isFree }.ifEmpty { healthy }
        } else {
            healthy
        }

        val toolFiltered = if (requiresTools) {
            tierPool.filter { it.supportsTools }.ifEmpty { tierPool }
        } else {
            tierPool
        }

        val ranked = toolFiltered
            .sortedByDescending { m ->
                var score = (m.contextLength / 2048).coerceAtMost(250)
                if (m.supportsTools) score += if (requiresTools) 1500 else 700
                if (freeOnly && m.isFree) score += 1200
                if (!freeOnly && m.id == "openrouter/auto") score += 2500
                if (freeOnly && m.id == "openrouter/free") score += if (requiresTools) 950 else 1600
                val lower = m.id.lowercase()
                if (lower.contains("gemini") || lower.contains("gpt-4o") || lower.contains("gpt-4.1")) score += 450
                if (lower.contains("qwen") || lower.contains("llama-3.3") || lower.contains("mistral") || lower.contains("gemma-3")) score += 340
                if (complexity == TaskComplexity.CODING && (lower.contains("coder") || lower.contains("deepseek") || lower.contains("claude"))) score += 350
                if (lower.contains("thinking") || lower.contains(":thinking")) score -= 400
                score
            }
            .map { it.id }
            .toMutableList()

        if (freeOnly && !ranked.contains("openrouter/free")) {
            val insertIdx = if (requiresTools) minOf(3, ranked.size) else 0
            ranked.add(insertIdx, "openrouter/free")
        } else if (!freeOnly && !ranked.contains("openrouter/auto")) {
            ranked.add(0, "openrouter/auto")
        }
        return ranked.distinct().take(8)
    }

    suspend fun generate(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        stream: Boolean,
        onStreamChunk: (String) -> Unit = {}
    ): Result<AiCompletionResult> = withContext(Dispatchers.IO) {
        val cleanKey = SecureKeyStore.sanitizeSecret(apiKey)
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("OpenRouter API key is empty or invalid."))
        }
        val body = buildRequestBody(model, systemPrompt, messages, tools, stream && tools.isEmpty())

        var lastErr: Exception? = null
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $cleanKey")
                    .addHeader("HTTP-Referer", "https://personal-ai-computer.local")
                    .addHeader("X-Title", "Personal AI Computer")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 429 || (code in 400..499)) {
                        val errText = response.body?.string().orEmpty()
                        val msg = extractError(errText)
                        if (code == 402 || msg.contains("credits", ignoreCase = true) || msg.contains("payment", ignoreCase = true)) {
                            markFreeTierOnly(cleanKey)
                            markModelFailure(model, 30 * 60_000L)
                        } else if (code == 429) {
                            markModelFailure(model, 90_000L)
                        } else if (code == 400 || code == 404 || code == 422 || msg.contains("tool", ignoreCase = true)) {
                            markModelFailure(model, 10 * 60_000L)
                        }
                        auditLogger.log("openrouter.ai", "OpenRouter ($model)", "HTTP $code")
                        return@withContext Result.failure(
                            IllegalStateException("OpenRouter error ($code): $msg")
                        )
                    }

                    if (code >= 500) {
                        val errText = response.body?.string().orEmpty()
                        lastErr = IllegalStateException("OpenRouter HTTP $code: ${extractError(errText)}")
                        if (attempt == 0) {
                            delay(300)
                            return@use
                        }
                        auditLogger.log("openrouter.ai", "OpenRouter ($model)", "HTTP $code")
                        return@withContext Result.failure(lastErr!!)
                    }

                    val res = if (stream && tools.isEmpty()) {
                        parseSseStream(response, model, onStreamChunk)
                    } else {
                        val raw = response.body?.string().orEmpty()
                        auditLogger.log("openrouter.ai", "OpenRouter ($model)", "200 OK", raw.length.toLong())
                        parseSingleResponse(raw, model)
                    }
                    return@withContext Result.success(res)
                }
            } catch (e: Exception) {
                lastErr = e
                if (attempt == 0) delay(300)
            }
        }
        auditLogger.log("openrouter.ai", "OpenRouter ($model)", "Failed: ${lastErr?.message}")
        Result.failure(lastErr ?: IllegalStateException("Unknown OpenRouter failure"))
    }

    internal fun buildRequestBody(
        model: String,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        stream: Boolean
    ): JSONObject {
        val msgsJson = JSONArray()
        if (systemPrompt.isNotBlank()) {
            msgsJson.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    if (msg.imageAttachments.isEmpty()) {
                        msgsJson.put(JSONObject().put("role", "user").put("content", msg.content))
                    } else {
                        val contentArr = JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", msg.content)
                        )
                        msg.imageAttachments.forEach { img ->
                            contentArr.put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put(
                                        "image_url",
                                        JSONObject().put("url", "data:${img.mimeType};base64,${img.base64Data}")
                                    )
                            )
                        }
                        msgsJson.put(JSONObject().put("role", "user").put("content", contentArr))
                    }
                }
                "assistant" -> {
                    val obj = JSONObject().put("role", "assistant")
                    // Always send string content (empty string when tool-only) so strict free providers never reject null
                    obj.put("content", msg.content)
                    if (msg.toolCalls.isNotEmpty()) {
                        val callsArr = JSONArray()
                        msg.toolCalls.forEach { call ->
                            callsArr.put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", call.name)
                                            .put("arguments", call.arguments.toString())
                                    )
                            )
                        }
                        obj.put("tool_calls", callsArr)
                    }
                    msgsJson.put(obj)
                }
                "tool" -> {
                    msgsJson.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", msg.toolCallId ?: "")
                            .put("content", msg.content)
                    )
                }
            }
        }

        val root = JSONObject()
            .put("model", model)
            .put("messages", msgsJson)
            .put("stream", stream)

        if (tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            tools.forEach { tool ->
                val params = tool.parametersSchema
                val props = params.optJSONObject("properties")
                val safeParams = if (props != null && props.length() > 0) {
                    params
                } else {
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "note",
                                JSONObject().put("type", "string").put("description", "Optional brief note")
                            )
                        )
                }
                toolsArr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", safeParams)
                        )
                )
            }
            root.put("tools", toolsArr)
        }
        return root
    }

    internal fun parseSingleResponse(raw: String, model: String): AiCompletionResult {
        val root = JSONObject(raw)
        if (root.has("error") && !root.isNull("error")) {
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message")?.takeIf { it.isNotBlank() } ?: raw.take(200)
            throw IllegalStateException("OpenRouter error: $msg")
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = optNullableString(message, "content")
        val toolCalls = mutableListOf<ToolCallRequest>()
        val callsArr = message?.optJSONArray("tool_calls")
        if (callsArr != null) {
            for (i in 0 until callsArr.length()) {
                val c = callsArr.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val fnName = optNullableString(fn, "name").trim()
                if (fnName.isBlank()) continue
                val argsJson = when (val rawArgs: Any? = fn.opt("arguments")) {
                    is JSONObject -> rawArgs
                    is String -> parseArgsSafely(rawArgs)
                    null -> JSONObject()
                    else -> JSONObject()
                }
                toolCalls.add(
                    ToolCallRequest(
                        id = optNullableString(c, "id").ifBlank { "or_call_${System.nanoTime()}_$i" },
                        name = fnName,
                        arguments = argsJson
                    )
                )
            }
        }

        val usage = root.optJSONObject("usage")
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank() && toolCalls.isEmpty()) {
            markModelFailure(model, 90_000L)
            throw IllegalStateException("OpenRouter ($model) returned empty response.")
        }
        return AiCompletionResult(
            provider = "OpenRouter",
            model = optNullableString(root, "model").ifBlank { model },
            text = trimmedContent,
            toolCalls = toolCalls,
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
        )
    }

    private fun parseArgsSafely(raw: String): JSONObject {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (trimmed.isEmpty()) return JSONObject()
        return runCatching { JSONObject(trimmed) }.getOrElse {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
        }
    }

    private fun optNullableString(obj: JSONObject?, key: String): String {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return ""
        return obj.optString(key, "")
    }

    private fun parseSseStream(
        response: okhttp3.Response,
        model: String,
        onStreamChunk: (String) -> Unit
    ): AiCompletionResult {
        val textSb = StringBuilder()
        var streamError: String? = null
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("data: ")) {
                    val payload = line.removePrefix("data: ").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        runCatching {
                            val obj = JSONObject(payload)
                            val errObj = obj.optJSONObject("error")
                            if (errObj != null) {
                                streamError = errObj.optString("message", "Stream error")
                            }
                            val deltaObj = obj.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                            val delta = optNullableString(deltaObj, "content")
                            if (delta.isNotEmpty()) {
                                textSb.append(delta)
                                onStreamChunk(delta)
                            }
                        }
                    }
                }
            }
        }
        if (!streamError.isNullOrBlank() && textSb.isEmpty()) {
            markModelFailure(model, 90_000L)
            throw IllegalStateException("OpenRouter stream error ($model): $streamError")
        }
        val finalText = textSb.toString().trim()
        if (finalText.isBlank()) {
            markModelFailure(model, 90_000L)
            throw IllegalStateException("OpenRouter stream ($model) returned empty output.")
        }
        auditLogger.log("openrouter.ai", "OpenRouter Stream ($model)", "200 OK", finalText.length.toLong())
        return AiCompletionResult(
            provider = "OpenRouter",
            model = model,
            text = finalText
        )
    }

    private fun extractError(raw: String): String {
        return runCatching {
            val obj = JSONObject(raw).optJSONObject("error")
            if (obj != null && !obj.isNull("message")) obj.optString("message") else raw.take(200)
        }.getOrElse { raw.take(200) }
    }
}
