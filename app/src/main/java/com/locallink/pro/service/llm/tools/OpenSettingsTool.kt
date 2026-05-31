package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class OpenSettingsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "open_settings"

    override val description: String =
        "Open a specific Android system settings screen (Wi-Fi, Bluetooth, display, sound, " +
            "airplane mode, location, or battery). Use this when the user wants to change a " +
            "device setting that the app cannot toggle directly."

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("screen", JSONObject().apply {
                put("type", "string")
                put(
                    "description",
                    "Which settings screen to open. One of: wifi, bluetooth, display, " +
                        "sound, airplane, location, battery."
                )
                put("enum", org.json.JSONArray().apply {
                    put("wifi")
                    put("bluetooth")
                    put("display")
                    put("sound")
                    put("airplane")
                    put("location")
                    put("battery")
                })
            })
        })
        put("required", org.json.JSONArray().apply { put("screen") })
    }.toString()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val raw = args.optString("screen", "").trim().lowercase()
        if (raw.isEmpty()) {
            return "Error: missing required argument 'screen'. " +
                "Expected one of: wifi, bluetooth, display, sound, airplane, location, battery."
        }

        val action: String = when (raw) {
            "wifi", "wi-fi", "wlan" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "screen", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "audio", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "airplane", "airplanemode", "airplane_mode", "flight" ->
                Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery", "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> return "Error: unknown screen '$raw'. " +
                "Expected one of: wifi, bluetooth, display, sound, airplane, location, battery."
        }

        return try {
            val intent = Intent(action)
            if (intent.resolveActivity(context.packageManager) == null) {
                "Error: this device has no activity to handle the '$raw' settings screen."
            } else {
                context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                "Opened the $raw settings screen."
            }
        } catch (e: Exception) {
            "Error: failed to open the $raw settings screen: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
