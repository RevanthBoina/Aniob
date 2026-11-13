package com.aniob.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionScoreEntity::class,
        AppKnowledgeBaseEntity::class,
        TipEntity::class,
        LogEventEntity::class,
        ActiveTaskEntity::class,
        WorkflowEntity::class,
        AuditLogEntity::class,
        FeatureCombinationEntity::class,
        TaskOutcomeEntity::class,
        ConversationTaskEntity::class,
        ConversationAuditEntity::class,
        ConversationEntity::class,
        ChatMessageEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AniobDatabase : RoomDatabase() {
    abstract fun featureCombinationDao(): FeatureCombinationDao
    abstract fun sessionScoreDao(): SessionScoreDao
    abstract fun appKnowledgeBaseDao(): AppKnowledgeBaseDao
    abstract fun tipDao(): TipDao
    abstract fun logEventDao(): LogEventDao
    abstract fun activeTaskDao(): ActiveTaskDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun taskOutcomeDao(): TaskOutcomeDao
    abstract fun conversationJournalDao(): ConversationJournalDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AniobDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_knowledge_base ADD COLUMN kind TEXT NOT NULL DEFAULT 'FASTPATH'")
                db.execSQL("ALTER TABLE app_knowledge_base ADD COLUMN artifactJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_knowledge_base ADD COLUMN reviewState TEXT NOT NULL DEFAULT 'VERIFIED'")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS audit_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "taskId TEXT NOT NULL, " +
                        "stepIndex INTEGER NOT NULL, " +
                        "actionType TEXT NOT NULL, " +
                        "targetPackage TEXT NOT NULL, " +
                        "sanitizedPayload TEXT NOT NULL, " +
                        "verificationStatus TEXT NOT NULL, " +
                        "previousHash TEXT NOT NULL, " +
                        "entryHash TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS feature_combination_runs (runId TEXT NOT NULL PRIMARY KEY, featureMask INTEGER NOT NULL, configurationKey TEXT NOT NULL, timestampMs INTEGER NOT NULL, runCsv TEXT NOT NULL)")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN status TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN stopReason TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastTaskId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_tasks (taskId TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, prompt TEXT NOT NULL, startedAt INTEGER NOT NULL, outcome TEXT NOT NULL, reason TEXT NOT NULL, response TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_tasks_conversationId ON conversation_tasks(conversationId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_audits (conversationId TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, report TEXT NOT NULL, source TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_audits_conversationId ON conversation_audits(conversationId)")
            }
        }

        fun getDatabase(context: Context): AniobDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AniobDatabase::class.java,
                    "aniob_database.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    WorkflowDatabaseMigrations.MIGRATION_2_3,
                    WorkflowDatabaseMigrations.MIGRATION_3_4,
                    MIGRATION_4_5,
                    OutcomeGuard.MIGRATION_5_6,
                    MIGRATION_6_7,
                    ConversationStore.MIGRATION_7_8,
                    MIGRATION_8_9
                ).addCallback(OutcomeGuard.callback).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
