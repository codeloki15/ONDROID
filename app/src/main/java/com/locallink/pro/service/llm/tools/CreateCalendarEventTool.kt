package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreateCalendarEventTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "create_calendar_event"

    override val description: String =
        "Open the calendar app to create a new event with a prefilled title, start/end time, " +
            "and optional location. The user confirms and saves the event in the calendar UI."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "The title/name of the calendar event."
            },
            "start_epoch_millis": {
              "type": "integer",
              "description": "Event start time as Unix epoch milliseconds."
            },
            "end_epoch_millis": {
              "type": "integer",
              "description": "Optional event end time as Unix epoch milliseconds. Defaults to start + 1 hour."
            },
            "location": {
              "type": "string",
              "description": "Optional event location."
            }
          },
          "required": ["title", "start_epoch_millis"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val title = args.optString("title", "").trim()
        if (title.isEmpty()) {
            return "Error: 'title' is required and must be a non-empty string."
        }

        val startMillis = args.optLong("start_epoch_millis", 0L)
        if (startMillis <= 0L) {
            return "Error: 'start_epoch_millis' is required and must be a positive epoch-milliseconds value."
        }

        val oneHourMillis = 60L * 60L * 1000L
        var endMillis = args.optLong("end_epoch_millis", 0L)
        if (endMillis <= 0L) {
            endMillis = startMillis + oneHourMillis
        }
        if (endMillis < startMillis) {
            endMillis = startMillis + oneHourMillis
        }

        val location = args.optString("location", "").trim()

        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                if (location.isNotEmpty()) {
                    putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return "Error: No calendar app is available to create the event."
            }

            context.startActivity(intent)

            val result = JSONObject().apply {
                put("status", "opened_calendar")
                put("title", title)
                put("start_epoch_millis", startMillis)
                put("end_epoch_millis", endMillis)
                if (location.isNotEmpty()) {
                    put("location", location)
                }
                put("message", "Opened the calendar app with the event prefilled. Ask the user to confirm and save it.")
            }
            result.toString()
        } catch (e: Exception) {
            "Error: Failed to open the calendar app to create the event: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
