package com.example.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC")
    fun observeAllTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId")
    fun observeTask(taskId: Long): Flow<AgentTaskEntity?>

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: Long): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentTasksSnapshot(limit: Int = 10): List<AgentTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: AgentTaskEntity): Long

    @Update
    suspend fun updateTask(task: AgentTaskEntity)

    @Query("DELETE FROM agent_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    @Query("DELETE FROM agent_tasks")
    suspend fun clearAllTasks()
}

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs WHERE taskId = :taskId ORDER BY id ASC")
    fun observeLogsForTask(taskId: Long): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentLogs(): Flow<List<ExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLogEntity): Long

    @Query("DELETE FROM execution_logs WHERE taskId = :taskId")
    suspend fun deleteLogsForTask(taskId: Long)

    @Query("DELETE FROM execution_logs")
    suspend fun clearAllLogs()
}

@Dao
interface AgentMemoryDao {
    @Query("SELECT * FROM agent_memory ORDER BY updatedAt DESC")
    fun observeAllMemory(): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memory ORDER BY updatedAt DESC")
    suspend fun getAllMemorySnapshot(): List<AgentMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: AgentMemoryEntity): Long

    @Query("DELETE FROM agent_memory WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM agent_memory")
    suspend fun clearAllMemory()
}

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY createdAt DESC")
    fun observeRules(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun getRuleById(id: Long): AutomationRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: AutomationRuleEntity): Long

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM browser_bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM browser_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}

@Dao
interface ProviderUsageDao {
    @Query("SELECT * FROM provider_usage ORDER BY timestamp DESC LIMIT 100")
    fun observeUsage(): Flow<List<ProviderUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsage(usage: ProviderUsageEntity)

    @Query("DELETE FROM provider_usage")
    suspend fun clearUsage()
}

@Dao
interface ImportedSkillDao {
    @Query("SELECT * FROM imported_skills ORDER BY importedAt DESC")
    fun observeAllSkills(): Flow<List<ImportedSkillEntity>>

    @Query("SELECT * FROM imported_skills WHERE enabled = 1 ORDER BY importedAt DESC")
    suspend fun getEnabledSkillsSnapshot(): List<ImportedSkillEntity>

    @Query("SELECT * FROM imported_skills ORDER BY importedAt DESC")
    suspend fun getAllSkillsSnapshot(): List<ImportedSkillEntity>

    @Query("SELECT * FROM imported_skills WHERE slug = :slug LIMIT 1")
    suspend fun getSkillBySlug(slug: String): ImportedSkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: ImportedSkillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkills(skills: List<ImportedSkillEntity>)

    @Query("DELETE FROM imported_skills WHERE slug = :slug")
    suspend fun deleteSkill(slug: String)

    @Query("DELETE FROM imported_skills")
    suspend fun clearAllSkills()
}

@Dao
interface WhatsAppChatDao {
    @Query("SELECT * FROM whatsapp_chats ORDER BY lastMessageAt DESC")
    fun observeAllChats(): Flow<List<WhatsAppChatEntity>>

    @Query("SELECT * FROM whatsapp_chats ORDER BY lastMessageAt DESC")
    suspend fun getAllChatsSnapshot(): List<WhatsAppChatEntity>

    @Query("SELECT * FROM whatsapp_chats WHERE chatKey = :chatKey LIMIT 1")
    suspend fun getChatByKey(chatKey: String): WhatsAppChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(chat: WhatsAppChatEntity)

    @Query("DELETE FROM whatsapp_chats WHERE chatKey = :chatKey")
    suspend fun deleteChat(chatKey: String)

    @Query("DELETE FROM whatsapp_chats")
    suspend fun clearAllChats()
}

@Dao
interface WhatsAppMessageDao {
    @Query("SELECT * FROM whatsapp_messages WHERE chatKey = :chatKey ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesForChat(chatKey: String, limit: Int = 20): List<WhatsAppMessageEntity>

    @Query("SELECT * FROM whatsapp_messages WHERE chatKey = :chatKey ORDER BY timestamp ASC LIMIT 80")
    fun observeMessagesForChat(chatKey: String): Flow<List<WhatsAppMessageEntity>>

    @Query("SELECT * FROM whatsapp_messages ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentActivity(): Flow<List<WhatsAppMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: WhatsAppMessageEntity): Long

    @Query("DELETE FROM whatsapp_messages WHERE chatKey = :chatKey")
    suspend fun deleteMessagesForChat(chatKey: String)

    @Query("DELETE FROM whatsapp_messages")
    suspend fun clearAllMessages()
}
