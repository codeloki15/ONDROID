package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Trailing non-user rows (assistant/tool/system) after the last user turn — used to regenerate. */
    @Query(
        "DELETE FROM messages WHERE sessionId = :sessionId AND timestamp > " +
            "(SELECT COALESCE(MAX(timestamp), 0) FROM messages WHERE sessionId = :sessionId AND role = 'user')"
    )
    suspend fun deleteAfterLastUser(sessionId: String)
}
