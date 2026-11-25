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
import java.util.concurrent.TimeUnit

class GeminiProvider(
    private val auditLogger: NetworkAuditLogger
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedModelsFetchedAt: Long = 0L
    @Volatile
    private var cachedDiscoveredModels: List<GeminiModelInfo> = emptyList()

    suspend fun fetchAvailableModels(apiKey: String, forceRefresh: Boolean = false): Result<List<GeminiModelInfo>> =
        withContext(Dispatchers.IO) {
            val cleanKey = SecureKeyStore.sanitizeSecret(apiKey)
            val freshCache = System.currentTimeMillis() - cachedModelsFetchedAt < 30 * 60_000L
            if (!forceRefresh && freshCache && cachedDiscoveredModels.isNotEmpty()) {
                return@withContext Result.success(cachedDiscoveredModels)
            }
            runCatching {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                    .addHeader("x-goog-api-key", cleanKey)
                    .get()
                    .build()
                discoveryClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "Gemini models HTTP ${resp.code}: ${extractError(raw)}" }
                    auditLogger.log("generativelanguage.googleapis.com", "Gemini Model Discovery", "200 OK", raw.length.toLong())
                    val arr = JSONObject(raw).optJSONArray("models") ?: JSONArray()
                    val list = mutableListOf<GeminiModelInfo>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val fullName = obj.optString("name")
                        val id = fullName.removePrefix("models/")
                        if (id.isBlank()) continue
                        val methods = obj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        val supportsGen = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                        if (!supportsGen) continue
                        val lower = id.lowercase()
                        // Exclude non-tool-capable or media/embedding models (e.g. gemma, learnlm, imagen, tts, thinking-exp)
                        if (!lower.startsWith("gemini-") ||
                            lower.contains("embedding") ||
                            lower.contains("imagen") ||
                            lower.contains("veo") ||
                            lower.contains("tts") ||
                            lower.contains("audio") ||
                            lower.contains("image") ||
                            lower.contains("live") ||
                            lower.contains("thinking") ||
                            lower.contains("robotics") ||
                            lower.contains("computer-use") ||
                            lower.contains("aqa")
                        ) continue
                        val displayName = obj.optString("displayName", id)
                        val inputLimit = obj.optInt("inputTokenLimit", 32768)
                        list.add(GeminiModelInfo(id = id, displayName = displayName, inputTokenLimit = inputLimit))
                    }
                    val sorted = list.sortedByDescending { scoreGeminiModel(it.id, TaskComplexity.MULTISTEP_AGENT, true) }
                    cachedModelsFetchedAt = System.currentTimeMillis()
                    cachedDiscoveredModels = sorted
                    sorted
                }
            }
        }

    suspend fun resolveCandidateModels(
        apiKey: String,
        requestedModel: String,
        complexity: TaskComplexity,
        preferFastOnSimple: Boolean
    ): List<String> {
        if (requestedModel.isNotBlank() && requestedModel != AUTO_MODEL_ID) {
            return listOf(requestedModel)
        }
        val discovered = fetchAvailableModels(apiKey).getOrElse { emptyList() }
        if (discovered.isEmpty()) {
            // Instant resilient fallback so discovery lag/errors never stall replies
            return listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")
        }
        return discovered
            .sortedByDescending { scoreGeminiModel(it.id, complexity, preferFastOnSimple) }
            .map { it.id }
            .distinct()
    }

    private fun scoreGeminiModel(id: String, complexity: TaskComplexity, preferFast: Boolean): Int {
        val lower = id.lowercase()
        var score = 0
        val verMatch = Regex("""(\d+)\.(\d+)""").find(lower)
        if (verMatch != null) {
            val major = verMatch.groupValues[1].toIntOrNull() ?: 1
            val minor = verMatch.groupValues[2].toIntOrNull() ?: 0
            score += (major * 200 + minor * 20)
        }
        val isLite = lower.contains("lite")
        val isFlash = lower.contains("flash")
        val isPro = lower.contains("pro")
        val isLatestAlias = lower.endsWith("-latest")
        val isExperimental = lower.contains("exp") || lower.contains("preview")

        // Strongly prioritize stable low-latency Flash models so replies never take 2-3 minutes
        score += when (complexity) {
            TaskComplexity.REASONING -> when {
                !preferFast && isPro && !isExperimental -> 850
                isFlash && !isLite && !isExperimental -> 820
                isFlash && !isLite -> 700
                isPro -> 500
                else -> 250
            }
            TaskComplexity.SIMPLE, TaskComplexity.CODING, TaskComplexity.MULTISTEP_AGENT -> when {
                lower == "gemini-2.5-flash" -> 1400
                lower == "gemini-2.5-flash-lite" && complexity == TaskComplexity.SIMPLE -> 1450
                isFlash && !isLite && !isExperimental -> 1150
                isFlash && isLite && !isExperimental -> 980
                isFlash && !isLite -> 750
                isPro && !isExperimental -> 480
                else -> 240
            }
        }
        if (isLatestAlias) score -= 40
        if (!isExperimental) score += 350 else score -= 250
        if (lower.contains("deprecated")) score -= 3000
        return score
    }

    suspend fun generate(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        stream: Boolean,
        fastMode: Boolean = false,
        onStreamChunk: (String) -> Unit = {}
    ): Result<AiCompletionResult> = withContext(Dispatchers.IO) {
        val cleanKey = SecureKeyStore.sanitizeSecret(apiKey)
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is empty or invalid."))
        }
        val bodyJson = buildRequestBody(model, systemPrompt, messages, tools, fastMode)
        val endpoint = if (stream && tools.isEmpty()) {
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
        } else {
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        }

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("x-goog-api-key", cleanKey)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    // Fast failover on 429/400/401/403/404 so AiModelRouter rotates immediately to next API key
                    if (code == 429 || (code in 400..499)) {
                        val errBody = response.body?.string().orEmpty()
                        auditLogger.log("generativelanguage.googleapis.com", "Gemini ($model)", "HTTP $code")
                        return@withContext Result.failure(
                            IllegalStateException("Gemini API error ($code): ${extractError(errBody)}")
                        )
                    }

                    if (code >= 500) {
                        val errBody = response.body?.string().orEmpty()
                        lastError = IllegalStateException("Gemini HTTP $code: ${extractError(errBody)}")
                        if (attempt == 0) {
                            delay(300)
                            return@use
                        }
                        auditLogger.log("generativelanguage.googleapis.com", "Gemini ($model)", "HTTP $code")
                        return@withContext Result.failure(lastError!!)
                    }

                    val result = if (stream && tools.isEmpty()) {
                        parseSseStream(response, model, onStreamChunk)
                    } else {
                        val raw = response.body?.string().orEmpty()
                        auditLogger.log("generativelanguage.googleapis.com", "Gemini ($model)", "200 OK", raw.length.toLong())
                        parseSingleResponse(raw, model)
                    }
                    return@withContext Result.success(result)
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) delay(300)
            }
        }
        auditLogger.log("generativelanguage.googleapis.com", "Gemini ($model)", "Failed: ${lastError?.message}")
        Result.failure(lastError ?: IllegalStateException("Unknown Gemini network failure"))
    }

    private fun buildRequestBody(
        model: String,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>,
        fastMode: Boolean
    ): JSONObject {
        val contents = JSONArray()
        var idx = 0
        while (idx < messages.size) {
            val msg = messages[idx]
            when (msg.role) {
                "user" -> {
                    val userParts = JSONArray().put(JSONObject().put("text", msg.content))
                    msg.imageAttachments.forEach { img ->
                        userParts.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject()
                                    .put("mimeType", img.mimeType)
                                    .put("data", img.base64Data)
                            )
                        )
                    }
                    contents.put(JSONObject().put("role", "user").put("parts", userParts))
                    idx++
                }
                "assistant" -> {
                    // Preserve exact model parts (including thoughtSignature for Gemini 2.5/3.0+) if available
                    val parts = if (!msg.rawAssistantPartsJson.isNullOrBlank()) {
                        runCatching { JSONArray(msg.rawAssistantPartsJson) }.getOrNull()
                    } else null

                    val finalParts = parts ?: JSONArray().apply {
                        if (msg.content.isNotBlank()) put(JSONObject().put("text", msg.content))
                        msg.toolCalls.forEach { call ->
                            val fcPart = JSONObject().put(
                                "functionCall",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("args", call.arguments)
                            )
                            if (!call.thoughtSignature.isNullOrBlank()) {
                                fcPart.put("thoughtSignature", call.thoughtSignature)
                            }
                            put(fcPart)
                        }
                    }
                    if (finalParts.length() > 0) {
                        contents.put(JSONObject().put("role", "model").put("parts", finalParts))
                    }
                    idx++
                }
                "tool" -> {
                    // Group ALL consecutive tool responses into a single 'user' turn per Gemini API spec
                    val toolParts = JSONArray()
                    while (idx < messages.size && messages[idx].role == "tool") {
                        val tMsg = messages[idx]
                        val fnName = tMsg.toolName ?: "tool"
                        val respObj = JSONObject().put("output", tMsg.content)
                        toolParts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject()
                                    .put("name", fnName)
                                    .put("response", respObj)
                            )
                        )
                        idx++
                    }
                    contents.put(JSONObject().put("role", "user").put("parts", toolParts))
                }
                else -> idx++
            }
        }

        val root = JSONObject().put("contents", contents)
        if (systemPrompt.isNotBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }
        val lowerModel = model.lowercase()
        val genConfig = JSONObject()
        if (lowerModel.contains("2.5-flash")) {
            // 0 thinking budget on quick greetings/fast mode; lean 512 on tool turns so responses arrive in ~2-5s
            genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", if (fastMode) 0 else 512))
        } else if (lowerModel.contains("gemini-3") && lowerModel.contains("flash")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", if (fastMode) "minimal" else "low"))
        }
        if (genConfig.length() > 0) {
            root.put("generationConfig", genConfig)
        }
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach { tool ->
                val fnObj = JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                // Omit empty parameters object (Gemini v1beta rejects empty object schemas)
                val props = tool.parametersSchema.optJSONObject("properties")
                if (props != null && props.length() > 0) {
                    fnObj.put("parameters", tool.parametersSchema)
                }
                decls.put(fnObj)
            }
            root.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        return root
    }

    private fun parseSingleResponse(raw: String, model: String): AiCompletionResult {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
        val first = candidates?.optJSONObject(0)
        val parts = first?.optJSONObject("content")?.optJSONArray("parts")
        val textSb = StringBuilder()
        val toolCalls = mutableListOf<ToolCallRequest>()

        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                if (p.has("text") && !p.isNull("text")) {
                    textSb.append(p.optString("text"))
                }
                if (p.has("functionCall")) {
                    val fc = p.optJSONObject("functionCall") ?: continue
                    val fnName = fc.optString("name").trim()
                    if (fnName.isBlank()) continue
                    val thoughtSig = if (!p.isNull("thoughtSignature")) p.optString("thoughtSignature").takeIf { it.isNotBlank() } else null
                    toolCalls.add(
                        ToolCallRequest(
                            id = "gemini_call_${System.nanoTime()}_$i",
                            name = fnName,
                            arguments = fc.optJSONObject("args") ?: JSONObject(),
                            thoughtSignature = thoughtSig
                        )
                    )
                }
            }
        }

        val usage = root.optJSONObject("usageMetadata")
        val promptTokens = usage?.optInt("promptTokenCount", 0) ?: 0
        val completionTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0

        return AiCompletionResult(
            provider = "Gemini",
            model = model,
            text = textSb.toString().trim(),
            toolCalls = toolCalls,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            rawAssistantPartsJson = parts?.toString()
        )
    }

    private fun parseSseStream(
        response: okhttp3.Response,
        model: String,
        onStreamChunk: (String) -> Unit
    ): AiCompletionResult {
        val textSb = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("data: ")) {
                    val payload = line.removePrefix("data: ").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        runCatching {
                            val obj = JSONObject(payload)
                            val partsArr = obj.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                            if (partsArr != null) {
                                for (i in 0 until partsArr.length()) {
                                    val p = partsArr.optJSONObject(i) ?: continue
                                    if (p.optBoolean("thought", false)) continue
                                    val chunkText = if (!p.isNull("text")) p.optString("text") else ""
                                    if (chunkText.isNotEmpty()) {
                                        textSb.append(chunkText)
                                        onStreamChunk(chunkText)
                                    }
                                }
                            }
                            val usage = obj.optJSONObject("usageMetadata")
                            if (usage != null) {
                                promptTokens = usage.optInt("promptTokenCount", promptTokens)
                                completionTokens = usage.optInt("candidatesTokenCount", completionTokens)
                            }
                        }
                    }
                }
            }
        }
        val finalText = textSb.toString()
        auditLogger.log("generativelanguage.googleapis.com", "Gemini Stream ($model)", "200 OK", finalText.length.toLong())
        return AiCompletionResult(
            provider = "Gemini",
            model = model,
            text = finalText.trim(),
            promptTokens = promptTokens,
            completionTokens = completionTokens
        )
    }

    private fun extractError(raw: String): String {
        return runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty().ifBlank { raw.take(200) }
        }.getOrElse { raw.take(200) }
    }
}
