package com.locallink.pro.service.notify

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.locallink.pro.data.db.NotificationRuleDao
import com.locallink.pro.data.db.NotificationRuleEntity
import com.locallink.pro.data.db.TriggerRunDao
import com.locallink.pro.data.db.TriggerRunEntity
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.service.voice.VoiceService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a trigger's action and records the run in the history log.
 * Shared by the notification listener and the time-trigger worker.
 */
@Singleton
class TriggerExecutor @Inject constructor(
    private val runs: TriggerRunDao,
    private val voice: VoiceService,
    private val chat: ChatRepository,
) {
    companion object { private const val TAG = "TriggerExec" }

    // The agent's completion callback may fire on the service's main-ish scope —
    // never block it; hop to IO for the DB write.
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    /**
     * @param sourceLine short human condition line for the history row, e.g.
     *   "Gmail notification" or "Daily 08:00".
     * @param context null for time triggers; the (appLabel, title, text) for notifications.
     */
    suspend fun execute(rule: NotificationRuleEntity, sourceLine: String, context: Triple<String, String, String>?) {
        val actionLine = if (rule.action == "agent")
            "task: ${rule.agentTask.ifBlank { "handle it" }.take(60)}" else "announce"
        val runId = runs.insert(
            TriggerRunEntity(
                ruleId = rule.id,
                description = "$sourceLine → $actionLine",
                status = "running",
                startedAt = System.currentTimeMillis(),
            )
        )
        runs.prune(System.currentTimeMillis() - 14L * 24 * 3600 * 1000)

        when (rule.action) {
            "agent" -> {
                var task = rule.agentTask.ifBlank {
                    if (context != null) "Handle this notification from {app}: {title}" else "Scheduled task"
                }
                if (context != null) {
                    val (app, title, text) = context
                    val hasVars = listOf("{app}", "{title}", "{text}").any { task.contains(it) }
                    task = task.replace("{app}", app).replace("{title}", title).replace("{text}", text)
                    if (!hasVars) {
                        task += "\n\n[Context: this was triggered by a notification from the $app app — " +
                            "\"$title\": \"$text\". Act on THIS notification inside $app.]"
                    }
                }
                if (rule.targetApp.isNotBlank() && !task.contains(rule.targetApp, ignoreCase = true)) {
                    task = "In the ${rule.targetApp} app: $task"
                }
                runCatching {
                    chat.runAgent(task) { ok, note ->
                        scope.launch {
                            runs.finish(runId, if (ok) "success" else "failed", note, System.currentTimeMillis())
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "agent trigger failed", it)
                    runs.finish(runId, "failed", it.message ?: "error", System.currentTimeMillis())
                }
            }
            else -> { // "speak"
                val line = if (context != null) {
                    // PRIVACY: never read notification content aloud — announce the app only.
                    "You have a notification from ${context.first}."
                } else {
                    // Time triggers announce the user-authored note.
                    rule.matchText.ifBlank { "Scheduled reminder." }
                }
                val ok = runCatching { voice.speakWhenReady(line) }.isSuccess
                runs.finish(runId, if (ok) "success" else "failed",
                    if (ok) "announced" else "TTS unavailable", System.currentTimeMillis())
            }
        }
    }
}

/** Schedules time-type trigger rules (daily, self-chaining — same pattern as routines). */
@Singleton
class TriggerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun name(id: Long) = "trigger_$id"

    fun schedule(id: Long, hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val req = OneTimeWorkRequestBuilder<TriggerWorker>()
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(TriggerWorker.KEY_ID to id))
            .addTag("trigger")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(id: Long) = WorkManager.getInstance(context).cancelUniqueWork(name(id))
}

/** Fires a scheduled time trigger, then re-enqueues tomorrow's occurrence. */
class TriggerWorker(
    context: Context,
    params: WorkerParameters,
) : androidx.work.CoroutineWorker(context, params) {

    companion object {
        const val KEY_ID = "trigger_rule_id"
        private const val TAG = "TriggerWorker"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun rules(): NotificationRuleDao
        fun executor(): TriggerExecutor
        fun triggerScheduler(): TriggerScheduler
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val rule = deps.rules().byId(id)
        if (rule == null || !rule.enabled || rule.triggerType != "time" || rule.timeHour < 0) {
            Log.i(TAG, "trigger $id gone/disabled — chain ends")
            return Result.success()
        }
        Log.i(TAG, "firing time trigger $id at ${rule.timeHour}:${rule.timeMinute}")
        runCatching {
            deps.executor().execute(
                rule,
                "Daily %02d:%02d".format(rule.timeHour, rule.timeMinute),
                context = null,
            )
        }.onFailure { Log.e(TAG, "time trigger failed", it) }
        deps.triggerScheduler().schedule(id, rule.timeHour, rule.timeMinute)
        return Result.success()
    }
}
