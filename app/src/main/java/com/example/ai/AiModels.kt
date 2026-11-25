package com.example.ai

import org.json.JSONObject

const val AUTO_MODEL_ID = "auto"

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
    val arguments: JSONObject,
    val thoughtSignature: String? = null
)

data class AiImageAttachment(
    val displayName: String,
    val mimeType: String,
    val base64Data: String
)

data class AiMessage(
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val rawAssistantPartsJson: String? = null,
    val imageAttachments: List<AiImageAttachment> = emptyList()
)

data class AiCompletionResult(
    val provider: String,
    val model: String,
    val text: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val rawAssistantPartsJson: String? = null,
    val keySlotLabel: String? = null
)

data class GeminiModelInfo(
    val id: String,
    val displayName: String,
    val inputTokenLimit: Int,
    val supportsTools: Boolean = true
)

data class OpenRouterModelInfo(
    val id: String,
    val name: String,
    val contextLength: Int,
    val isFree: Boolean,
    val supportsTools: Boolean = true
)
