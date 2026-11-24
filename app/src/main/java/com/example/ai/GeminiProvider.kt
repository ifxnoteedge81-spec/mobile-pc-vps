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

    val availableModels = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite"
    )

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
