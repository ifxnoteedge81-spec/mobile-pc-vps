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

class OpenRouterProvider(
    private val auditLogger: NetworkAuditLogger
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedModels: List<OpenRouterModelInfo> = emptyList()
    @Volatile
    private var cachedIsFreeTierOnly: Boolean? = null

    suspend fun isKeyFreeTierOnly(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        cachedIsFreeTierOnly?.let { return@withContext it }
        val result = runCatching {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/auth/key")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use true
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONObject("data") ?: return@use true
                val isFreeTier = data.optBoolean("is_free_tier", true)
                val limitRemaining = data.optDouble("limit_remaining", -1.0)
                isFreeTier && limitRemaining <= 0.0
            }
        }.getOrDefault(false)
        cachedIsFreeTierOnly = result
        result
    }

    suspend fun fetchAvailableModels(apiKey: String?): Result<List<OpenRouterModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .get()
            if (!apiKey.isNullOrBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "OpenRouter models HTTP ${resp.code}" }
                auditLogger.log("openrouter.ai", "Model Discovery", "200 OK", raw.length.toLong())
                val arr = JSONObject(raw).optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<OpenRouterModelInfo>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val name = obj.optString("name", id)
                    val ctx = obj.optInt("context_length", 8192)
                    val pricing = obj.optJSONObject("pricing")
                    val promptPrice = pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0
                    val compPrice = pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0
                    val isFree = id.endsWith(":free") || (promptPrice == 0.0 && compPrice == 0.0)
                    val supportedParams = obj.optJSONArray("supported_parameters")
                    val supportsTools = if (supportedParams != null) {
                        (0 until supportedParams.length()).any { supportedParams.optString(it) == "tools" }
                    } else true
                    list.add(OpenRouterModelInfo(id, name, ctx, isFree, supportsTools))
                }
                val sorted = list.sortedWith(
                    compareByDescending<OpenRouterModelInfo> { it.isFree }
                        .thenByDescending { it.supportsTools }
                        .thenByDescending { it.contextLength }
                        .thenBy { it.name }
                )
                cachedModels = sorted
                sorted
            }
        }
    }

    suspend fun resolveCandidateModels(
        apiKey: String,
        requestedModel: String,
        complexity: TaskComplexity,
        requiresTools: Boolean,
        preferFreeOnSimple: Boolean
    ): List<String> {
        if (requestedModel.isNotBlank() && requestedModel != AUTO_MODEL_ID) {
            return listOf(requestedModel)
        }
        val freeOnly = isKeyFreeTierOnly(apiKey)
        val models = cachedModels.ifEmpty {
            fetchAvailableModels(apiKey).getOrElse { emptyList() }
        }
        if (models.isEmpty()) {
            return listOf("openrouter/auto")
        }

        val toolFiltered = if (requiresTools) models.filter { it.supportsTools }.ifEmpty { models } else models
        val tierFiltered = if (freeOnly || (preferFreeOnSimple && complexity == TaskComplexity.SIMPLE)) {
            toolFiltered.filter { it.isFree }.ifEmpty { toolFiltered }
        } else {
            toolFiltered
        }

        val ranked = tierFiltered
            .sortedByDescending { m ->
                var score = m.contextLength / 1024
                if (m.supportsTools) score += 500
                if (freeOnly && m.isFree) score += 1000
                if (!freeOnly && m.id == "openrouter/auto") score += 2000
                if (complexity == TaskComplexity.CODING && (m.id.contains("coder", true) || m.id.contains("claude", true) || m.id.contains("deepseek", true))) score += 400
                if (m.id.contains("gemini", true) || m.id.contains("qwen", true) || m.id.contains("llama", true)) score += 200
                score
            }
            .map { it.id }
            .toMutableList()

        if (!freeOnly && !ranked.contains("openrouter/auto")) {
            ranked.add("openrouter/auto")
        }
        return ranked.distinct().take(6)
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
        val body = buildRequestBody(model, systemPrompt, messages, tools, stream && tools.isEmpty())

        var lastErr: Exception? = null
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "https://personal-ai-computer.local")
                    .addHeader("X-Title", "Personal AI Computer")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 429 || code >= 500) {
                        val errText = response.body?.string().orEmpty()
                        lastErr = IllegalStateException("OpenRouter HTTP $code: ${extractError(errText)}")
                        if (attempt == 0) {
                            delay(1000)
                            return@use
                        }
                        auditLogger.log("openrouter.ai", "OpenRouter ($model)", "HTTP $code")
                        return@withContext Result.failure(lastErr!!)
                    }

                    if (!response.isSuccessful) {
                        val errText = response.body?.string().orEmpty()
                        auditLogger.log("openrouter.ai", "OpenRouter ($model)", "Error $code")
                        return@withContext Result.failure(
                            IllegalStateException("OpenRouter error ($code): ${extractError(errText)}")
                        )
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
                if (attempt == 0) delay(800)
            }
        }
        auditLogger.log("openrouter.ai", "OpenRouter ($model)", "Failed: ${lastErr?.message}")
        Result.failure(lastErr ?: IllegalStateException("Unknown OpenRouter failure"))
    }

    private fun buildRequestBody(
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
                "user" -> msgsJson.put(JSONObject().put("role", "user").put("content", msg.content))
                "assistant" -> {
                    val obj = JSONObject().put("role", "assistant")
                    if (msg.content.isNotBlank()) obj.put("content", msg.content)
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
                toolsArr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", tool.parametersSchema)
                        )
                )
            }
            root.put("tools", toolsArr)
        }
        return root
    }

    private fun parseSingleResponse(raw: String, model: String): AiCompletionResult {
        val root = JSONObject(raw)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        val toolCalls = mutableListOf<ToolCallRequest>()
        val callsArr = message?.optJSONArray("tool_calls")
        if (callsArr != null) {
            for (i in 0 until callsArr.length()) {
                val c = callsArr.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val argsRaw = fn.optString("arguments", "{}")
                val argsJson = runCatching { JSONObject(argsRaw) }.getOrElse { JSONObject() }
                toolCalls.add(
                    ToolCallRequest(
                        id = c.optString("id", "or_call_$i"),
                        name = fn.getString("name"),
                        arguments = argsJson
                    )
                )
            }
        }

        val usage = root.optJSONObject("usage")
        return AiCompletionResult(
            provider = "OpenRouter",
            model = root.optString("model", model),
            text = content.trim(),
            toolCalls = toolCalls,
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
        )
    }

    private fun parseSseStream(
        response: okhttp3.Response,
        model: String,
        onStreamChunk: (String) -> Unit
    ): AiCompletionResult {
        val textSb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("data: ")) {
                    val payload = line.removePrefix("data: ").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        runCatching {
                            val obj = JSONObject(payload)
                            val delta = obj.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content")
                                .orEmpty()
                            if (delta.isNotEmpty()) {
                                textSb.append(delta)
                                onStreamChunk(delta)
                            }
                        }
                    }
                }
            }
        }
        val finalText = textSb.toString()
        auditLogger.log("openrouter.ai", "OpenRouter Stream ($model)", "200 OK", finalText.length.toLong())
        return AiCompletionResult(
            provider = "OpenRouter",
            model = model,
            text = finalText.trim()
        )
    }

    private fun extractError(raw: String): String {
        return runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty().ifBlank { raw.take(180) }
        }.getOrElse { raw.take(180) }
    }
}
