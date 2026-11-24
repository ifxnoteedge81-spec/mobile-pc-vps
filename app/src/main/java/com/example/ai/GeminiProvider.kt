package com.example.ai

import com.example.security.NetworkAuditLogger
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
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedKeyHash: Int = 0
    @Volatile
    private var cachedDiscoveredModels: List<GeminiModelInfo> = emptyList()

    suspend fun fetchAvailableModels(apiKey: String, forceRefresh: Boolean = false): Result<List<GeminiModelInfo>> =
        withContext(Dispatchers.IO) {
            val hash = apiKey.hashCode()
            if (!forceRefresh && cachedKeyHash == hash && cachedDiscoveredModels.isNotEmpty()) {
                return@withContext Result.success(cachedDiscoveredModels)
            }
            runCatching {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                    .addHeader("x-goog-api-key", apiKey)
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
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
                        if (lower.contains("embedding") ||
                            lower.contains("imagen") ||
                            lower.contains("veo") ||
                            lower.contains("tts") ||
                            lower.contains("audio") ||
                            lower.contains("robotics") ||
                            lower.contains("aqa")
                        ) continue
                        val displayName = obj.optString("displayName", id)
                        val inputLimit = obj.optInt("inputTokenLimit", 32768)
                        list.add(GeminiModelInfo(id = id, displayName = displayName, inputTokenLimit = inputLimit))
                    }
                    val sorted = list.sortedByDescending { scoreGeminiModel(it.id, TaskComplexity.MULTISTEP_AGENT, false) }
                    cachedKeyHash = hash
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
            return emptyList()
        }
        return discovered
            .sortedByDescending { scoreGeminiModel(it.id, complexity, preferFastOnSimple) }
            .map { it.id }
            .distinct()
    }

    private fun scoreGeminiModel(id: String, complexity: TaskComplexity, preferFast: Boolean): Int {
        val lower = id.lowercase()
        var score = 0
        // Extract version hint dynamically (e.g., 2.5 -> 250, 2.0 -> 200, 3.0 -> 300)
        val verMatch = Regex("""(\d+)\.(\d+)""").find(lower)
        if (verMatch != null) {
            val major = verMatch.groupValues[1].toIntOrNull() ?: 1
            val minor = verMatch.groupValues[2].toIntOrNull() ?: 0
            score += major * 1000 + minor * 100
        }
        val isLite = lower.contains("lite")
        val isFlash = lower.contains("flash")
        val isPro = lower.contains("pro")
        val isLatestAlias = lower.endsWith("-latest")
        val isExperimental = lower.contains("exp") || lower.contains("preview")

        score += when (complexity) {
            TaskComplexity.SIMPLE -> when {
                preferFast && isFlash && !isLite -> 400
                preferFast && isLite -> 360
                isFlash -> 350
                else -> 200
            }
            TaskComplexity.CODING, TaskComplexity.REASONING, TaskComplexity.MULTISTEP_AGENT -> when {
                preferFast && isFlash -> 380
                isPro -> 420
                isFlash && !isLite -> 360
                else -> 220
            }
        }
        if (isLatestAlias) score += 80
        if (!isExperimental) score += 40
        if (lower.contains("deprecated")) score -= 2000
        return score
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
        val bodyJson = buildRequestBody(systemPrompt, messages, tools)
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
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 429 || code >= 500) {
                        val errBody = response.body?.string().orEmpty()
                        lastError = IllegalStateException("Gemini HTTP $code: ${extractError(errBody)}")
                        if (attempt == 0) {
                            delay(1000)
                            return@use
                        }
                        auditLogger.log("generativelanguage.googleapis.com", "Gemini ($model)", "HTTP $code")
                        return@withContext Result.failure(lastError!!)
                    }

                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty()
                        auditLogger.log("generativelanguage.googleapis.com", "Gemini ($model)", "Error $code")
                        return@withContext Result.failure(
                            IllegalStateException("Gemini API error ($code): ${extractError(errBody)}")
                        )
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
                if (attempt == 0) delay(800)
            }
        }
        auditLogger.log("generativelanguage.googleapis.com", "Gemini ($model)", "Failed: ${lastError?.message}")
        Result.failure(lastError ?: IllegalStateException("Unknown Gemini network failure"))
    }

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<ToolDeclaration>
    ): JSONObject {
        val contents = JSONArray()
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    contents.put(
                        JSONObject().put("role", "user").put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", msg.content))
                        )
                    )
                }
                "assistant" -> {
                    val parts = JSONArray()
                    if (msg.content.isNotBlank()) parts.put(JSONObject().put("text", msg.content))
                    msg.toolCalls.forEach { call ->
                        parts.put(
                            JSONObject().put(
                                "functionCall",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("args", call.arguments)
                            )
                        )
                    }
                    if (parts.length() > 0) {
                        contents.put(JSONObject().put("role", "model").put("parts", parts))
                    }
                }
                "tool" -> {
                    val fnName = msg.toolName ?: "tool"
                    val respObj = JSONObject().put("output", msg.content)
                    contents.put(
                        JSONObject().put("role", "user").put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "functionResponse",
                                    JSONObject()
                                        .put("name", fnName)
                                        .put("response", respObj)
                                )
                            )
                        )
                    )
                }
            }
        }

        val root = JSONObject().put("contents", contents)
        if (systemPrompt.isNotBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach { tool ->
                decls.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", tool.parametersSchema)
                )
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
                if (p.has("text")) textSb.append(p.optString("text"))
                if (p.has("functionCall")) {
                    val fc = p.getJSONObject("functionCall")
                    toolCalls.add(
                        ToolCallRequest(
                            id = "gemini_call_${System.nanoTime()}_$i",
                            name = fc.getString("name"),
                            arguments = fc.optJSONObject("args") ?: JSONObject()
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
            completionTokens = completionTokens
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
                            val chunkText = obj.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                .orEmpty()
                            if (chunkText.isNotEmpty()) {
                                textSb.append(chunkText)
                                onStreamChunk(chunkText)
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
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty().ifBlank { raw.take(180) }
        }.getOrElse { raw.take(180) }
    }
}
