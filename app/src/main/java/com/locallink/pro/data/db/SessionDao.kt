package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One session summarised for the activity dashboard: what was asked, when, whether it was
 * spoken, and whether Omni drove the phone for it.
 *
 * `voice` and `agent` are derived from the messages already stored — a voice turn is flagged on
 * the message, and an agent run always writes tool_call rows — so the feed needs no extra
 * bookkeeping at write time.
 */
data class SessionActivity(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val voice: Boolean,
    val agent: Boolean,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT s.id AS id, s.title AS title, s.updatedAt AS updatedAt,
               MAX(CASE WHEN m.isVoice THEN 1 ELSE 0 END) AS voice,
               MAX(CASE WHEN m.role = 'tool_call' THEN 1 ELSE 0 END) AS agent
        FROM sessions s LEFT JOIN messages m ON m.sessionId = s.id
        GROUP BY s.id, s.title, s.updatedAt
        ORDER BY s.updatedAt DESC
        LIMIT 100
        """
    )
    fun observeActivity(): Flow<List<SessionActivity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
