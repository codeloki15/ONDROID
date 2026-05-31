package com.locallink.pro.service.llm.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadCalendarTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "read_calendar"

    override val description: String =
        "Read upcoming calendar events. Returns a JSON array of up to 10 events " +
            "(title, start time in ISO-8601, and location) occurring between now and " +
            "the next N days. Requires calendar read permission."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "days_ahead": {
              "type": "integer",
              "description": "How many days ahead from now to include events for. Defaults to 1.",
              "minimum": 1
            }
          },
          "required": []
        }
    """.trimIndent()

    override val readOnly: Boolean = true

    override suspend fun execute(args: JSONObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Permission android.permission.READ_CALENDAR not granted. Cannot read calendar."
        }

        var daysAhead = args.optInt("days_ahead", 1)
        if (daysAhead < 1) daysAhead = 1

        val now = System.currentTimeMillis()
        val end = now + daysAhead.toLong() * 86_400_000L

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION
        )

        val builder: Uri.Builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, end)
        val uri = builder.build()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        val results = JSONArray()
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            if (cursor == null) {
                return "Failed to query calendar: no data available."
            }

            val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val locationIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

            var count = 0
            while (cursor.moveToNext() && count < 10) {
                val title = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                val begin = if (beginIdx >= 0) cursor.getLong(beginIdx) else 0L
                val location = if (locationIdx >= 0) cursor.getString(locationIdx) else null

                val event = JSONObject()
                event.put("title", title ?: "(no title)")
                event.put("start_iso", if (begin > 0L) isoFormat.format(Date(begin)) else "")
                event.put("location", location ?: "")
                results.put(event)
                count++
            }

            if (results.length() == 0) {
                "No events found in the next $daysAhead day(s)."
            } else {
                results.toString()
            }
        } catch (e: Exception) {
            "Failed to read calendar: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            cursor?.close()
        }
    }
}
