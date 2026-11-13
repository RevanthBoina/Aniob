package com.aniob.app.db

import androidx.room.*
import androidx.room.withTransaction

/** Durable task evidence, including failed/unanswered requests. Not model conversation context. */
@Entity(tableName = "conversation_tasks", foreignKeys = [ForeignKey(
    entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
    onDelete = ForeignKey.CASCADE)], indices = [Index("conversationId")])
data class ConversationTaskEntity(
    @PrimaryKey val taskId: String,
    val conversationId: String,
    val prompt: String,
    val startedAt: Long,
    val outcome: String = "RUNNING",
    val reason: String = "",
    val response: String = ""
)

@Entity(tableName = "conversation_audits", foreignKeys = [ForeignKey(
    entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
    onDelete = ForeignKey.CASCADE)], indices = [Index("conversationId")])
data class ConversationAuditEntity(
    @PrimaryKey val conversationId: String,
    val createdAt: Long,
    val report: String,
    val source: String
)

@Dao
interface ConversationJournalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun begin(task: ConversationTaskEntity)
    @Query("UPDATE conversation_tasks SET outcome = :outcome, reason = :reason, response = :response WHERE taskId = :id AND outcome = 'RUNNING'")
    suspend fun finish(id: String, outcome: String, reason: String, response: String)
    @Query("SELECT * FROM conversation_tasks WHERE conversationId = :id ORDER BY startedAt, taskId")
    suspend fun tasks(id: String): List<ConversationTaskEntity>
    @Query("SELECT * FROM conversation_tasks WHERE taskId = :id")
    suspend fun task(id: String): ConversationTaskEntity?
    @Query("SELECT * FROM conversation_tasks WHERE outcome = 'RUNNING' AND startedAt < :before")
    suspend fun interrupted(before: Long): List<ConversationTaskEntity>
    @Upsert suspend fun saveAudit(report: ConversationAuditEntity)
    @Query("SELECT * FROM conversation_audits WHERE conversationId = :id")
    suspend fun audit(id: String): ConversationAuditEntity?
}

/** Parent/task updates commit together; late closures cannot overwrite a newer task's status. */
class ConversationJournalStore(private val db: AniobDatabase) {
    suspend fun begin(task: ConversationTaskEntity) = db.withTransaction {
        ConversationStore(db.conversationDao()).beginTask(task.conversationId, task.taskId, task.prompt)
        db.conversationJournalDao().begin(task)
    }
    suspend fun finish(taskId: String, outcome: String, reason: String, response: String = "") = db.withTransaction {
        val task = db.conversationJournalDao().task(taskId) ?: return@withTransaction
        if (task.outcome != "RUNNING") return@withTransaction
        db.conversationJournalDao().finish(taskId, outcome, reason, response)
        ConversationStore(db.conversationDao()).finishTask(task.conversationId, taskId, outcome, reason)
    }
    suspend fun recover(before: Long) {
        db.conversationJournalDao().interrupted(before).forEach {
            finish(it.taskId, "INTERRUPTED", "Process ended before a terminal outcome was recorded; cause unknown")
        }
    }
}
