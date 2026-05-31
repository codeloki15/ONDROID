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
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SendNotificationTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "send_notification"

    override val description: String =
        "Post a system notification on the device immediately. Provide a title and a message body."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "The notification title (short headline)."
            },
            "message": {
              "type": "string",
              "description": "The notification body text."
            }
          },
          "required": ["title", "message"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        return try {
            val title = args.optString("title", "").trim()
            val message = args.optString("message", "").trim()

            if (title.isEmpty() && message.isEmpty()) {
                return "Error: at least one of 'title' or 'message' must be provided."
            }

            // On API 33+ posting notifications requires the POST_NOTIFICATIONS permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    return "Error: permission POST_NOTIFICATIONS not granted; cannot post the notification. Please grant notification permission and try again."
                }
            }

            ensureChannel()

            val safeTitle = if (title.isEmpty()) "Notification" else title

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(safeTitle)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val notificationId = idGenerator.incrementAndGet()

            try {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
            } catch (se: SecurityException) {
                return "Error: unable to post notification (permission denied): ${se.message}"
            }

            "Notification posted successfully (id=$notificationId) with title \"$safeTitle\"."
        } catch (e: Exception) {
            "Error: failed to post notification: ${e.message ?: e.toString()}"
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "OmniPin Alerts",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Notifications posted by OmniPin tools."
                    }
                    manager.createNotificationChannel(channel)
                }
            } catch (_: Exception) {
                // Channel creation is best-effort; notify() will still attempt to post.
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "omni_alerts"
        val idGenerator = AtomicInteger(7000)
    }
}
