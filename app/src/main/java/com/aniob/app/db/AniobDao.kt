package com.aniob.app.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionScoreDao {
    @Query("SELECT * FROM session_scores ORDER BY timestamp DESC")
    fun getAllScores(): Flow<List<SessionScoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScore(score: SessionScoreEntity): Long

    @Query("SELECT COUNT(*) FROM session_scores")
    suspend fun countScores(): Int

    @Query("SELECT * FROM session_scores WHERE status = :status ORDER BY timestamp DESC")
    fun getScoresByStatus(status: String): Flow<List<SessionScoreEntity>>

    /** One-shot "last N" for the task report; newest first. */
    @Query("SELECT * FROM session_scores ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<SessionScoreEntity>

    /** One-shot lookup of one task's record, for the task report. */
    @Query("SELECT * FROM session_scores WHERE taskId = :taskId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getByTaskId(taskId: String): SessionScoreEntity?

    /**
     * A 👍 on an unverified/failed run is user attestation of success: the row flips to SUCCESS
     * so task history (Successful/Unsuccessful) shows what the user experienced, matching the
     * sealed outcome's USER_ATTESTATION promotion. Never silently downgrades an existing SUCCESS.
     */
    @Query("UPDATE session_scores SET status = 'SUCCESS', decisionReason = decisionReason || ' [user-attested]' WHERE taskId = :taskId AND status != 'SUCCESS'")
    suspend fun markUserAttestedSuccess(taskId: String): Int
}

@Dao
interface AppKnowledgeBaseDao {
    @Query("SELECT * FROM app_knowledge_base WHERE taskSignature = :signature LIMIT 1")
    suspend fun findBySignature(signature: String): AppKnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMacro(entry: AppKnowledgeBaseEntity)

    @Query("SELECT * FROM app_knowledge_base ORDER BY lastUpdated DESC")
    fun getAllKnowledge(): Flow<List<AppKnowledgeBaseEntity>>

    /** Every persisted trajectory, used to rehydrate the FastPath engine at cold start. */
    @Query("SELECT * FROM app_knowledge_base")
    suspend fun getAllKnowledgeOnce(): List<AppKnowledgeBaseEntity>

    @Query("SELECT * FROM app_knowledge_base WHERE kind = :kind")
    suspend fun getKnowledgeByKind(kind: String): List<AppKnowledgeBaseEntity>

    @Query("SELECT * FROM app_knowledge_base WHERE reviewState = :reviewState ORDER BY lastUpdated DESC")
    fun getKnowledgeByReviewState(reviewState: String): Flow<List<AppKnowledgeBaseEntity>>

    @Query("UPDATE app_knowledge_base SET reviewState = :reviewState WHERE taskSignature = :signature")
    suspend fun updateReviewState(signature: String, reviewState: String)

    @Query("DELETE FROM app_knowledge_base WHERE taskSignature = :signature")
    suspend fun deleteBySignature(signature: String)
}

@Dao
interface TipDao {
    @Query("SELECT * FROM learned_tips WHERE packageName = :packageName ORDER BY confidence DESC")
    suspend fun getTipsForPackage(packageName: String): List<TipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTip(tip: TipEntity): Long
}

@Dao
interface LogEventDao {
    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<LogEventEntity>>

    @Insert
    suspend fun insertLog(log: LogEventEntity): Long

    @Query("DELETE FROM event_logs WHERE id NOT IN (SELECT id FROM event_logs ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneOldLogs(keepCount: Int = 1000)
}

@Dao
interface ActiveTaskDao {
    @Query("SELECT * FROM active_tasks ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveTask(): ActiveTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActiveTask(task: ActiveTaskEntity)

    @Query("DELETE FROM active_tasks")
    suspend fun clearActiveTasks()

    @Query("DELETE FROM active_tasks WHERE taskId = :taskId")
    suspend fun clearTask(taskId: String)
}
