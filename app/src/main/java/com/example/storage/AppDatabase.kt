package com.example.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentTaskEntity::class,
        ExecutionLogEntity::class,
        AgentMemoryEntity::class,
        AutomationRuleEntity::class,
        BookmarkEntity::class,
        ProviderUsageEntity::class,
        ImportedSkillEntity::class
    ],
    version = 2,
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

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "personal_ai_computer.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
