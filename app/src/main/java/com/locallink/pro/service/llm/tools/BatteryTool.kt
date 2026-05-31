package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "get_battery_status"

    override val description: String =
        "Get the device's current battery level (percent) and whether it is currently charging."

    override val parametersJson: String =
        """{"type":"object","properties":{},"required":[]}"""

    override val readOnly: Boolean = true

    override suspend fun execute(args: JSONObject): String {
        return try {
            val batteryManager =
                context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

            val percent = batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?: -1

            val isCharging = try {
                val batteryStatus = context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                val status = batteryStatus?.getIntExtra(
                    BatteryManager.EXTRA_STATUS,
                    -1
                ) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            } catch (e: Exception) {
                false
            }

            val percentText = if (percent in 0..100) "$percent%" else "unknown%"
            val chargingText = if (isCharging) "charging" else "not charging"

            "$percentText , $chargingText"
        } catch (e: Exception) {
            "Error reading battery status: ${e.message ?: "unknown error"}"
        }
    }
}
