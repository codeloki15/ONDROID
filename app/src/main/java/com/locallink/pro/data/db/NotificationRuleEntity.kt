package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * An automation trigger (IFTTT-style).
 *
 * Condition — [triggerType]:
 *  - "notification": a notification from [appPackage] (label or package substring;
 *    "" = any app) whose title/text contains [matchText] ("" = any; 'or'/comma = alternatives)
 *  - "time": fires daily at [timeHour]:[timeMinute]; [matchText] doubles as the
 *    reminder note / display label
 *
 * Action — [action]:
 *  - "speak": announce it. For notifications this NEVER includes the content —
 *    only "You have a notification from <app>" (privacy). For time triggers the
 *    note itself is the announcement (user-authored).
 *  - "agent": run [agentTask] through Automate, acting in [targetApp] when set.
 */
@Entity(tableName = "notification_rules")
data class NotificationRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appPackage: String = "",
    val matchText: String = "",
    val action: String = "speak",
    val agentTask: String = "",
    val enabled: Boolean = true,
    val createdAt: Long,
    val triggerType: String = "notification",   // "notification" | "time"
    val timeHour: Int = -1,
    val timeMinute: Int = -1,
    /** App label the agent task should act in ("" = let the task decide). */
    val targetApp: String = "",
)

/** One execution of a trigger — the history screen's row. */
@Entity(tableName = "trigger_runs")
data class TriggerRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    /** Human line: "Gmail notification → reply task" / "Daily 08:00 → announce". */
    val description: String,
    val status: String,          // "running" | "success" | "failed"
    val detail: String = "",     // failure reason / outcome note
    val startedAt: Long,
    val finishedAt: Long = 0,
)

@Dao
interface NotificationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: NotificationRuleEntity): Long

    @Query("SELECT * FROM notification_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NotificationRuleEntity>>

    @Query("SELECT * FROM notification_rules WHERE enabled = 1 AND triggerType = 'notification'")
    suspend fun enabled(): List<NotificationRuleEntity>

    @Query("SELECT * FROM notification_rules WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): NotificationRuleEntity?

    @Query("UPDATE notification_rules SET enabled = :on WHERE id = :id")
    suspend fun setEnabled(id: Long, on: Boolean)

    @Query("DELETE FROM notification_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TriggerRunDao {
    @Insert
    suspend fun insert(r: TriggerRunEntity): Long

    @Query("UPDATE trigger_runs SET status = :status, detail = :detail, finishedAt = :ts WHERE id = :id")
    suspend fun finish(id: Long, status: String, detail: String, ts: Long)

    @Query("SELECT * FROM trigger_runs ORDER BY startedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<TriggerRunEntity>>

    @Query("DELETE FROM trigger_runs WHERE startedAt < :cutoff")
    suspend fun prune(cutoff: Long)

    @Query("DELETE FROM trigger_runs")
    suspend fun clear()
}
