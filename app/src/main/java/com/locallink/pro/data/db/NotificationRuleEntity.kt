package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A notification trigger: when a notification matching [appPackage]/[matchText] arrives,
 * Omni reacts — speaks it aloud or hands a task to the Automate agent.
 */
@Entity(tableName = "notification_rules")
data class NotificationRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Package substring to match ("" = any app), e.g. "whatsapp". */
    val appPackage: String = "",
    /** Case-insensitive substring the title/text must contain ("" = any). */
    val matchText: String = "",
    /** "speak" = read it aloud; "agent" = run [agentTask] through Automate. */
    val action: String = "speak",
    /** Agent task template; "{app}", "{title}" and "{text}" are substituted. */
    val agentTask: String = "",
    val enabled: Boolean = true,
    val createdAt: Long,
)

@Dao
interface NotificationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: NotificationRuleEntity): Long

    @Query("SELECT * FROM notification_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NotificationRuleEntity>>

    @Query("SELECT * FROM notification_rules WHERE enabled = 1")
    suspend fun enabled(): List<NotificationRuleEntity>

    @Query("UPDATE notification_rules SET enabled = :on WHERE id = :id")
    suspend fun setEnabled(id: Long, on: Boolean)

    @Query("DELETE FROM notification_rules WHERE id = :id")
    suspend fun delete(id: Long)
}
