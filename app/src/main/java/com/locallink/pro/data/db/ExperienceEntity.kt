package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A learned routine: the successful action trace of a past pilot run, keyed by the
 * normalized task text. Lets the agent replay known tasks deterministically instead
 * of re-reasoning (and re-guessing) with the LLM every time.
 */
@Entity(tableName = "experiences", indices = [Index(value = ["taskNorm"], unique = true)])
data class ExperienceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskNorm: String,       // normalized task text (match key)
    val taskRaw: String,        // original phrasing (display)
    val stepsJson: String,      // serialized List<TraceStep>
    val successCount: Int = 1,  // how many times this routine has worked
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface ExperienceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: ExperienceEntity): Long

    @Query("SELECT * FROM experiences WHERE taskNorm = :norm LIMIT 1")
    suspend fun findByNorm(norm: String): ExperienceEntity?

    @Query("SELECT * FROM experiences ORDER BY updatedAt DESC")
    suspend fun all(): List<ExperienceEntity>

    @Query("UPDATE experiences SET successCount = successCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun bumpSuccess(id: Long, now: Long)

    @Query("DELETE FROM experiences WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM experiences")
    suspend fun deleteAll()
}
