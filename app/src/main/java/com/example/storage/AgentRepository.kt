package com.example.storage

import com.example.agent.UniversalSkillManager
import com.example.security.CommandSecurityGuard
import kotlinx.coroutines.flow.Flow

class AgentRepository(private val db: AppDatabase) : UniversalSkillManager.SkillStore {
    private val taskDao = db.agentTaskDao()
    private val logDao = db.executionLogDao()
    private val memoryDao = db.agentMemoryDao()
    private val ruleDao = db.automationRuleDao()
    private val bookmarkDao = db.bookmarkDao()
    private val usageDao = db.providerUsageDao()
    private val skillDao = db.importedSkillDao()
    private val waChatDao = db.whatsAppChatDao()
    private val waMessageDao = db.whatsAppMessageDao()
    private val waScheduleDao = db.whatsAppScheduleDao()

    val tasks: Flow<List<AgentTaskEntity>> = taskDao.observeAllTasks()
    val recentLogs: Flow<List<ExecutionLogEntity>> = logDao.observeRecentLogs()
    val memories: Flow<List<AgentMemoryEntity>> = memoryDao.observeAllMemory()
    val automationRules: Flow<List<AutomationRuleEntity>> = ruleDao.observeRules()
    val bookmarks: Flow<List<BookmarkEntity>> = bookmarkDao.observeBookmarks()
    val providerUsage: Flow<List<ProviderUsageEntity>> = usageDao.observeUsage()
    val importedSkills: Flow<List<ImportedSkillEntity>> = skillDao.observeAllSkills()
    val whatsAppChats: Flow<List<WhatsAppChatEntity>> = waChatDao.observeAllChats()
    val whatsAppActivity: Flow<List<WhatsAppMessageEntity>> = waMessageDao.observeRecentActivity()
    val whatsAppSchedules: Flow<List<WhatsAppScheduledMessageEntity>> = waScheduleDao.observeAllSchedules()

    fun observeTask(taskId: Long): Flow<AgentTaskEntity?> = taskDao.observeTask(taskId)
    fun observeLogsForTask(taskId: Long): Flow<List<ExecutionLogEntity>> = logDao.observeLogsForTask(taskId)
    fun observeWhatsAppMessages(chatKey: String): Flow<List<WhatsAppMessageEntity>> =
        waMessageDao.observeMessagesForChat(chatKey)

    suspend fun getTask(taskId: Long): AgentTaskEntity? = taskDao.getTaskById(taskId)
    suspend fun getRecentTasksSnapshot(limit: Int = 10): List<AgentTaskEntity> = taskDao.getRecentTasksSnapshot(limit)

    suspend fun createTask(title: String, prompt: String, modelUsed: String): Long {
        return taskDao.insertTask(
            AgentTaskEntity(
                title = title.take(80),
                prompt = prompt,
                status = TaskStatus.PLANNING,
                modelUsed = modelUsed
            )
        )
    }

    suspend fun updateTask(task: AgentTaskEntity) {
        taskDao.updateTask(task.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteTask(taskId: Long) {
        logDao.deleteLogsForTask(taskId)
        taskDao.deleteTask(taskId)
    }

    suspend fun appendLog(
        taskId: Long,
        stepNumber: Int,
        entryType: String,
        title: String,
        detail: String,
        verified: Boolean = false
    ): Long {
        return logDao.insertLog(
            ExecutionLogEntity(
                taskId = taskId,
                stepNumber = stepNumber,
                entryType = entryType,
                title = title,
                detail = detail,
                verified = verified
            )
        )
    }

    suspend fun getMemorySnapshot(): List<AgentMemoryEntity> = memoryDao.getAllMemorySnapshot()

    suspend fun saveMemory(category: String, key: String, content: String, existingId: Long = 0): Result<Long> {
        if (CommandSecurityGuard.containsSensitiveCredential(content) ||
            CommandSecurityGuard.containsSensitiveCredential(key)
        ) {
            return Result.failure(
                IllegalArgumentException("Security Policy: Passwords, OTPs, and API keys cannot be saved in Agent Memory.")
            )
        }
        val id = memoryDao.upsertMemory(
            AgentMemoryEntity(
                id = existingId,
                category = category,
                memoryKey = key.trim(),
                content = content.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
        return Result.success(id)
    }

    suspend fun deleteMemory(id: Long) = memoryDao.deleteMemory(id)

    suspend fun saveAutomationRule(rule: AutomationRuleEntity): Long = ruleDao.upsertRule(rule)
    suspend fun getAutomationRule(id: Long): AutomationRuleEntity? = ruleDao.getRuleById(id)
    suspend fun getEnabledAutomationRules(): List<AutomationRuleEntity> = ruleDao.getEnabledRules()
    suspend fun deleteAutomationRule(id: Long) = ruleDao.deleteRule(id)

    suspend fun addBookmark(title: String, url: String): Long =
        bookmarkDao.insertBookmark(BookmarkEntity(title = title, url = url))

    suspend fun deleteBookmark(id: Long) = bookmarkDao.deleteBookmark(id)

    suspend fun recordUsage(provider: String, modelId: String, promptTokens: Int, completionTokens: Int) {
        usageDao.insertUsage(
            ProviderUsageEntity(
                provider = provider,
                modelId = modelId,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )
        )
    }

    suspend fun getEnabledImportedSkillsSnapshot(): List<ImportedSkillEntity> = skillDao.getEnabledSkillsSnapshot()
    override suspend fun getAllImportedSkillsSnapshot(): List<ImportedSkillEntity> = skillDao.getAllSkillsSnapshot()
    override suspend fun getImportedSkillBySlug(slug: String): ImportedSkillEntity? = skillDao.getSkillBySlug(slug)
    override suspend fun upsertImportedSkill(skill: ImportedSkillEntity) = skillDao.upsertSkill(skill)
    override suspend fun upsertImportedSkills(skills: List<ImportedSkillEntity>) = skillDao.upsertSkills(skills)
    override suspend fun deleteImportedSkill(slug: String) = skillDao.deleteSkill(slug)
    override suspend fun clearAllImportedSkills() = skillDao.clearAllSkills()

    suspend fun getAllWhatsAppChatsSnapshot(): List<WhatsAppChatEntity> = waChatDao.getAllChatsSnapshot()
    suspend fun getWhatsAppChatByKey(chatKey: String): WhatsAppChatEntity? = waChatDao.getChatByKey(chatKey)
    suspend fun upsertWhatsAppChat(chat: WhatsAppChatEntity) = waChatDao.upsertChat(chat)
    suspend fun deleteWhatsAppChat(chatKey: String) {
        waMessageDao.deleteMessagesForChat(chatKey)
        waChatDao.deleteChat(chatKey)
    }

    suspend fun getRecentWhatsAppMessages(chatKey: String, limit: Int = 14): List<WhatsAppMessageEntity> =
        waMessageDao.getRecentMessagesForChat(chatKey, limit).asReversed()

    suspend fun insertWhatsAppMessage(msg: WhatsAppMessageEntity): Long = waMessageDao.insertMessage(msg)

    suspend fun getWhatsAppScheduleById(id: Long): WhatsAppScheduledMessageEntity? =
        waScheduleDao.getScheduleById(id)

    suspend fun getDueWhatsAppSchedules(nowMillis: Long): List<WhatsAppScheduledMessageEntity> =
        waScheduleDao.getDuePendingSchedules(nowMillis)

    suspend fun getPendingWhatsAppSchedulesSnapshot(): List<WhatsAppScheduledMessageEntity> =
        waScheduleDao.getPendingSchedulesSnapshot()

    suspend fun insertWhatsAppSchedule(item: WhatsAppScheduledMessageEntity): Long =
        waScheduleDao.insertSchedule(item)

    suspend fun insertWhatsAppSchedules(items: List<WhatsAppScheduledMessageEntity>): List<Long> =
        waScheduleDao.insertSchedules(items)

    suspend fun updateWhatsAppSchedule(item: WhatsAppScheduledMessageEntity) =
        waScheduleDao.updateSchedule(item)

    suspend fun deleteWhatsAppSchedule(id: Long) = waScheduleDao.deleteSchedule(id)

    suspend fun clearAllUserData() {
        logDao.clearAllLogs()
        taskDao.clearAllTasks()
        memoryDao.clearAllMemory()
        usageDao.clearUsage()
        skillDao.clearAllSkills()
        waMessageDao.clearAllMessages()
        waChatDao.clearAllChats()
        waScheduleDao.clearAllSchedules()
    }
}
