package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class SetTimerTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "set_timer"

    override val description: String =
        "Set a countdown timer on the device for a given number of seconds, with an optional label. " +
            "The timer is created via the system clock app and starts immediately."

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("seconds", JSONObject().apply {
                put("type", "integer")
                put("description", "Timer duration in seconds (must be greater than 0).")
            })
            put("label", JSONObject().apply {
                put("type", "string")
                put("description", "Optional label/message shown for the timer.")
            })
        })
        put("required", org.json.JSONArray().apply { put("seconds") })
    }.toString()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val seconds = args.optInt("seconds", -1)
        if (seconds <= 0) {
            return "Error: 'seconds' must be a positive integer greater than 0."
        }

        val label = args.optString("label", "").trim()

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return "Error: No clock app on this device can handle setting a timer."
            }

            context.startActivity(intent)

            val labelPart = if (label.isNotEmpty()) " labeled \"$label\"" else ""
            "Timer set for $seconds second${if (seconds == 1) "" else "s"}$labelPart."
        } catch (e: Exception) {
            "Error: Failed to set timer - ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
