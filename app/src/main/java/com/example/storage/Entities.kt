package com.example.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

object TaskStatus {
    const val PENDING = "PENDING"
    const val PLANNING = "PLANNING"
    const val RUNNING = "RUNNING"
    const val WAITING_PERMISSION = "WAITING_PERMISSION"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object MemoryCategory {
    const val USER_PROFILE = "User Profile"
    const val USER_PREFERENCES = "User Preferences"
    const val TASK_HISTORY = "Task History"
    const val WORKING_CONTEXT = "Working Context"
    const val SAVED_INSTRUCTIONS = "Saved Instructions"
    const val AUTOMATION_RULES = "Automation Rules"
    const val PROJECT_INFO = "Project Information"

    val all = listOf(
        USER_PROFILE,
        USER_PREFERENCES,
        SAVED_INSTRUCTIONS,
        WORKING_CONTEXT,
        AUTOMATION_RULES,
        PROJECT_INFO,
        TASK_HISTORY
    )
}

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val prompt: String,
    val status: String = TaskStatus.PENDING,
    val planJson: String = "[]",
    val finalReport: String = "",
    val modelUsed: String = "",
    val tokensUsed: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val stepNumber: Int,
    val entryType: String, // PLAN, TOOL_CALL, TOOL_RESULT, AI_RESPONSE, PERMISSION, ERROR, VERIFICATION
    val title: String,
    val detail: String,
    val verified: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_memory")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val memoryKey: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val prompt: String,
    val scheduleType: String, // ONE_TIME, DAILY, WEEKLY, MONTHLY, CONDITIONAL
    val hourOfDay: Int = 9,
    val minuteOfHour: Int = 0,
    val conditionExpr: String = "",
    val enabled: Boolean = true,
    val retryOnFailure: Boolean = true,
    val lastRunAt: Long? = null,
    val lastStatus: String = "Never run",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "browser_bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "provider_usage")
data class ProviderUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val modelId: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "imported_skills")
data class ImportedSkillEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val ecosystem: String, // Anthropic, OpenClaw, Gemini, MCP/OpenAI, Script, Universal
    val description: String,
    val entryPath: String, // relative to virtual_env e.g. skills/my_skill/SKILL.md
    val bundledFilesSummary: String = "",
    val toolMappingSummary: String = "",
    val tocSummary: String = "",
    val wordCount: Int = 0,
    val lineCount: Int = 0,
    val sourceOrigin: String = "",
    val enabled: Boolean = true,
    val importedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "whatsapp_chats")
data class WhatsAppChatEntity(
    @PrimaryKey val chatKey: String,
    val displayName: String,
    val isGroup: Boolean = false,
    val autoReplyEnabled: Boolean = false,
    val antiLinkEnabled: Boolean = false,
    val antiStickerEnabled: Boolean = false,
    val antiForwardEnabled: Boolean = false,
    val commandsEnabled: Boolean = true,
    val customTopicPrompt: String = "",
    val lastMessagePreview: String = "",
    val lastMessageAt: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0
)

@Entity(tableName = "whatsapp_messages")
data class WhatsAppMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatKey: String,
    val chatName: String,
    val senderName: String,
    val isFromMe: Boolean = false,
    val isGroup: Boolean = false,
    val body: String,
    val messageType: String = "TEXT", // TEXT, LINK, STICKER, COMMAND, AI_REPLY, MODERATION
    val sourceChannel: String = "WA_WEB", // WA_WEB, NOTIFICATION, IN_APP
    val deletedByModeration: Boolean = false,
    val latencyMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "whatsapp_schedules")
data class WhatsAppScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatKey: String,
    val targetDisplay: String,
    val isGroup: Boolean = false,
    val messageTemplate: String,
    val useAiCompose: Boolean = false,
    val scheduledAtMillis: Long,
    val campaignId: String = "",
    val dayIndex: Int = 1,
    val totalDays: Int = 1,
    val status: String = "PENDING", // PENDING, SENT, FAILED, CANCELLED
    val lastResult: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
