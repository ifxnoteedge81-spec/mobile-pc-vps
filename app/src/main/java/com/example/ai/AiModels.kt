package com.example.ai

import org.json.JSONObject

enum class TaskComplexity {
    SIMPLE,
    CODING,
    REASONING,
    MULTISTEP_AGENT
}

data class ToolDeclaration(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject
)

data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

data class AiMessage(
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

data class AiCompletionResult(
    val provider: String,
    val model: String,
    val text: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
)

data class OpenRouterModelInfo(
    val id: String,
    val name: String,
    val contextLength: Int,
    val isFree: Boolean
)
