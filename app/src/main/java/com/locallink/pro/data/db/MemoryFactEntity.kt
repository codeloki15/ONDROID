package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One persistent fact about the user ("wife_phone" → "+91…", "home_address" → "…").
 * Injected into every model prompt so the agent stops asking for the same details
 * and can complete tasks like "call my wife" without a follow-up question.
 */
@Entity(tableName = "memory_facts", indices = [Index(value = ["key"], unique = true)])
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable snake_case key, e.g. "wife_phone", "work_address", "preferred_language". */
    val key: String,
    val value: String,
    /** "user" = typed in Settings; "chat" = auto-extracted from conversation. */
    val source: String = "user",
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface MemoryFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: MemoryFactEntity): Long

    @Query("SELECT * FROM memory_facts WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts ORDER BY updatedAt DESC")
    suspend fun all(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryFactEntity>>

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll()
}
