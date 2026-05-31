package com.locallink.pro.service.llm.tools

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DateTimeTool @Inject constructor() : ToolHandler {

    override val name: String = "get_current_datetime"

    override val description: String =
        "Returns the current local date, time, day of the week, and timezone. Takes no arguments."

    override val parametersJson: String =
        """{"type":"object","properties":{},"required":[]}"""

    override val readOnly: Boolean = true

    override suspend fun execute(args: JSONObject): String {
        return try {
            val zoneId: ZoneId = ZoneId.systemDefault()
            val now: ZonedDateTime = ZonedDateTime.now(zoneId)
            val localDateTime: LocalDateTime = now.toLocalDateTime()
            val dayOfWeek: DayOfWeek = localDateTime.dayOfWeek

            val dateStr = localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()))
            val timeStr = localDateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()))
            val dayName = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            val zoneName = zoneId.id
            val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }

            JSONObject().apply {
                put("date", dateStr)
                put("time", timeStr)
                put("day_of_week", dayName)
                put("timezone", zoneName)
                put("utc_offset", offset)
                put("iso8601", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            }.toString()
        } catch (e: Exception) {
            "Error getting current date/time: ${e.message ?: "unknown error"}"
        }
    }
}
