package com.example.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AgentTaskEntity::class,
        ExecutionLogEntity::class,
        AgentMemoryEntity::class,
        AutomationRuleEntity::class,
        BookmarkEntity::class,
        ProviderUsageEntity::class,
        ImportedSkillEntity::class,
        WhatsAppChatEntity::class,
        WhatsAppMessageEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun providerUsageDao(): ProviderUsageDao
    abstract fun importedSkillDao(): ImportedSkillDao
    abstract fun whatsAppChatDao(): WhatsAppChatDao
    abstract fun whatsAppMessageDao(): WhatsAppMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `imported_skills` (
                        `slug` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `ecosystem` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `entryPath` TEXT NOT NULL,
                        `bundledFilesSummary` TEXT NOT NULL,
                        `toolMappingSummary` TEXT NOT NULL,
                        `tocSummary` TEXT NOT NULL,
                        `wordCount` INTEGER NOT NULL,
                        `lineCount` INTEGER NOT NULL,
                        `sourceOrigin` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `importedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whatsapp_chats` (
                        `chatKey` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `isGroup` INTEGER NOT NULL,
                        `autoReplyEnabled` INTEGER NOT NULL,
                        `antiLinkEnabled` INTEGER NOT NULL,
                        `antiStickerEnabled` INTEGER NOT NULL,
                        `antiForwardEnabled` INTEGER NOT NULL,
                        `commandsEnabled` INTEGER NOT NULL,
                        `customTopicPrompt` TEXT NOT NULL,
                        `lastMessagePreview` TEXT NOT NULL,
                        `lastMessageAt` INTEGER NOT NULL,
                        `unreadCount` INTEGER NOT NULL,
                        PRIMARY KEY(`chatKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whatsapp_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `chatKey` TEXT NOT NULL,
                        `chatName` TEXT NOT NULL,
                        `senderName` TEXT NOT NULL,
                        `isFromMe` INTEGER NOT NULL,
                        `isGroup` INTEGER NOT NULL,
                        `body` TEXT NOT NULL,
                        `messageType` TEXT NOT NULL,
                        `sourceChannel` TEXT NOT NULL,
                        `deletedByModeration` INTEGER NOT NULL,
                        `latencyMs` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "personal_ai_computer.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
