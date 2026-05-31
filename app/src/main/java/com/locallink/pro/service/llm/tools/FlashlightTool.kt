package com.locallink.pro.service.llm.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FlashlightTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "toggle_flashlight"

    override val description: String =
        "Turn the device's camera flashlight (torch) on or off."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "on": {
              "type": "boolean",
              "description": "true to turn the flashlight on, false to turn it off"
            }
          },
          "required": ["on"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val on = args.optBoolean("on", false)

        val cameraManager = try {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        } catch (e: Exception) {
            null
        } ?: return "Error: camera service is not available on this device."

        val torchCameraId: String? = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            return "Error: unable to query camera devices: ${e.message ?: "unknown error"}"
        }

        if (torchCameraId == null) {
            return "Error: no flashlight (torch) is available on this device."
        }

        return try {
            cameraManager.setTorchMode(torchCameraId, on)
            if (on) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            "Error: failed to set flashlight state: ${e.message ?: "unknown error"}"
        }
    }
}
