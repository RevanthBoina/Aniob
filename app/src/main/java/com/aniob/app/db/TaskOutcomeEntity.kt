package com.aniob.app.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One closed-set outcome per task. Written ONLY through [OutcomeStore] (which takes a `SealedOutcome`);
 * [OutcomeGuard] additionally installs SQLite triggers so even a raw insert cannot store a VERIFIED row
 * without an evidence reference, or any outcome outside the closed set.
 */
@Entity(tableName = "task_outcomes")
data class TaskOutcomeEntity(
    @PrimaryKey val taskId: String,
    /** ANSWERED | DELEGATED | VERIFIED | UNVERIFIED | FAILED | STOPPED */
    val outcome: String,
    val reason: String,
    /** Newline-separated `EvidenceRef.encode()` values. Mandatory when outcome == VERIFIED. */
    val evidenceRef: String?,
    /** YES | NO | null: the user's thumbs. */
    val userDecision: String?,
    val recordedAtMs: Long
)

@Dao
interface TaskOutcomeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskOutcomeEntity)

    @Query("SELECT * FROM task_outcomes WHERE taskId = :taskId LIMIT 1")
    suspend fun get(taskId: String): TaskOutcomeEntity?

    /** Honest success rate: only rows the DEVICE confirmed. User-attested rows are counted separately. */
    @Query("SELECT COUNT(*) FROM task_outcomes WHERE outcome = 'VERIFIED' AND evidenceRef NOT LIKE 'USER_ATTESTATION|%'")
    suspend fun countMachineVerified(): Int

    @Query("SELECT COUNT(*) FROM task_outcomes WHERE outcome = :outcome")
    suspend fun count(outcome: String): Int
}
