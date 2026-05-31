package com.locallink.pro.service.llm.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleReminderTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "schedule_reminder"

    override val description: String =
        "Schedule a local notification reminder to appear after a given delay in minutes. " +
            "Posts a notification with the provided message once the delay elapses."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "message": {
              "type": "string",
              "description": "The reminder text to show in the notification."
            },
            "delay_minutes": {
              "type": "integer",
              "description": "How many minutes from now to fire the reminder. Minimum 0.",
              "minimum": 0
            }
          },
          "required": ["message", "delay_minutes"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        return try {
            val message = args.optString("message", "").trim()
            if (message.isEmpty()) {
                return "Error: 'message' is required and must be a non-empty string."
            }

            var delayMinutes = args.optInt("delay_minutes", -1)
            if (delayMinutes < 0) {
                // Tolerate alternate encodings (e.g. string) defensively.
                val asString = args.optString("delay_minutes", "")
                delayMinutes = asString.toIntOrNull() ?: -1
            }
            if (delayMinutes < 0) {
                return "Error: 'delay_minutes' is required and must be a non-negative integer."
            }
            if (delayMinutes > 60 * 24 * 30) {
                return "Error: 'delay_minutes' is too large (max 43200, i.e. 30 days)."
            }

            // Ensure the notification channel exists ahead of time.
            ensureChannel()

            // Check POST_NOTIFICATIONS on API 33+. We still schedule even if denied.
            var permissionWarning = ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    permissionWarning =
                        " Warning: permission POST_NOTIFICATIONS not granted; the reminder is scheduled " +
                            "but the notification may not be shown until the permission is granted."
                }
            }

            val inputData: Data = workDataOf(
                ReminderWorker.KEY_MESSAGE to message
            )

            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
                .setInputData(inputData)
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueue(request)

            val whenText = if (delayMinutes == 0) {
                "shortly"
            } else {
                "in $delayMinutes minute${if (delayMinutes == 1) "" else "s"}"
            }
            "Reminder scheduled $whenText: \"$message\".$permissionWarning"
        } catch (t: Throwable) {
            "Error: failed to schedule reminder: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Reminders",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Scheduled reminder notifications"
                    }
                    manager.createNotificationChannel(channel)
                }
            } catch (_: Throwable) {
                // Channel creation is best-effort; ignore failures.
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val WORK_TAG = "schedule_reminder_work"
    }

    /**
     * Inline worker that posts the reminder notification when its delay elapses.
     * Kept in the same file to stay self-contained (no manifest receiver needed).
     */
    class ReminderWorker(
        appContext: Context,
        params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            return try {
                val message = inputData.getString(KEY_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "You have a reminder."

                // Ensure channel exists at fire time too (in case it was cleared).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val manager = applicationContext
                        .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (manager != null &&
                        manager.getNotificationChannel(CHANNEL_ID) == null
                    ) {
                        val channel = NotificationChannel(
                            CHANNEL_ID,
                            "Reminders",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "Scheduled reminder notifications"
                        }
                        manager.createNotificationChannel(channel)
                    }
                }

                val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Reminder")
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()

                // Respect POST_NOTIFICATIONS on API 33+; skip silently if denied.
                val canPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                if (canPost) {
                    val notificationId =
                        (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                    try {
                        NotificationManagerCompat.from(applicationContext)
                            .notify(notificationId, notification)
                    } catch (_: SecurityException) {
                        // Permission revoked between check and post; ignore.
                    }
                }
                Result.success()
            } catch (_: Throwable) {
                // Never crash WorkManager; treat as handled.
                Result.success()
            }
        }

        companion object {
            const val KEY_MESSAGE = "reminder_message"
        }
    }
}
