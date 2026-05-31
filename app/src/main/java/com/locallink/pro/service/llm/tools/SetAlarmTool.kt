package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetAlarmTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "set_alarm"

    override val description: String =
        "Set a device alarm at a given time. Provide hour (0-23) and minute (0-59), " +
            "with an optional label. Returns whether the alarm was scheduled."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "hour": {
              "type": "integer",
              "description": "Hour of day in 24-hour format, 0-23",
              "minimum": 0,
              "maximum": 23
            },
            "minute": {
              "type": "integer",
              "description": "Minute of the hour, 0-59",
              "minimum": 0,
              "maximum": 59
            },
            "label": {
              "type": "string",
              "description": "Optional label/message for the alarm"
            }
          },
          "required": ["hour", "minute"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        // Read defensively; sentinel of -1 lets us detect missing values.
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        val label = args.optString("label", "").trim()

        if (hour < 0 || hour > 23) {
            return "Error: 'hour' must be an integer between 0 and 23 (got ${args.opt("hour")})."
        }
        if (minute < 0 || minute > 59) {
            return "Error: 'minute' must be an integer between 0 and 59 (got ${args.opt("minute")})."
        }

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (label.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return "Error: No app on this device can set alarms (AlarmClock.ACTION_SET_ALARM unsupported)."
            }

            context.startActivity(intent)

            val timeStr = String.format("%02d:%02d", hour, minute)
            if (label.isNotEmpty()) {
                "Alarm set for $timeStr with label \"$label\"."
            } else {
                "Alarm set for $timeStr."
            }
        } catch (e: Exception) {
            "Error: Failed to set alarm: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
