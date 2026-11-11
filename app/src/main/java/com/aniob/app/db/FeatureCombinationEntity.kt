package com.aniob.app.db

import androidx.room.*

/** Multi-feature experiments never enter the single-feature research CSV or summaries. */
@Entity(tableName = "feature_combination_runs")
data class FeatureCombinationEntity(
    @PrimaryKey val runId: String,
    val featureMask: Int,
    val configurationKey: String,
    val timestampMs: Long,
    val runCsv: String
)

@Dao
interface FeatureCombinationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(run: FeatureCombinationEntity)
    @Query("SELECT * FROM feature_combination_runs WHERE runId = :runId")
    fun find(runId: String): FeatureCombinationEntity?
    @Query("SELECT * FROM feature_combination_runs ORDER BY timestampMs DESC")
    fun all(): List<FeatureCombinationEntity>
}
