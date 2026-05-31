package com.locallink.pro.service.llm.tools

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetVolumeTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "set_volume"

    override val description: String =
        "Set the device volume for a given audio stream to a percentage (0-100). " +
            "Supported streams: music (media), ring, alarm. Defaults to music."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "percent": {
              "type": "integer",
              "description": "Target volume as a percentage from 0 to 100",
              "minimum": 0,
              "maximum": 100
            },
            "stream": {
              "type": "string",
              "description": "Which audio stream to set",
              "enum": ["music", "ring", "alarm"],
              "default": "music"
            }
          },
          "required": ["percent"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        return try {
            var percent = args.optInt("percent", -1)
            if (percent < 0) {
                // Fall back to parsing a possible string/double value defensively.
                val asDouble = args.optDouble("percent", Double.NaN)
                if (!asDouble.isNaN()) {
                    percent = asDouble.toInt()
                }
            }
            if (percent < 0) {
                return "Error: missing required 'percent' (expected an integer 0-100)."
            }
            // Clamp to valid range instead of failing.
            percent = percent.coerceIn(0, 100)

            val streamName = args.optString("stream", "music")
                .trim()
                .lowercase()
                .ifEmpty { "music" }

            val streamType = when (streamName) {
                "music", "media" -> AudioManager.STREAM_MUSIC
                "ring", "ringer", "ringtone" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> return "Error: unsupported stream '$streamName'. " +
                    "Use one of: music, ring, alarm."
            }

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "Error: AudioManager unavailable on this device."

            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            if (maxVolume <= 0) {
                return "Error: stream '$streamName' reports no adjustable volume range."
            }

            // Map percent -> index, rounding to nearest step.
            val targetIndex = Math.round(percent / 100.0 * maxVolume).toInt()
                .coerceIn(0, maxVolume)

            audioManager.setStreamVolume(streamType, targetIndex, 0)

            val actualIndex = audioManager.getStreamVolume(streamType)
            val actualPercent = Math.round(actualIndex / maxVolume.toDouble() * 100.0).toInt()

            JSONObject().apply {
                put("ok", true)
                put("stream", streamName)
                put("requested_percent", percent)
                put("volume_index", actualIndex)
                put("max_index", maxVolume)
                put("actual_percent", actualPercent)
            }.toString()
        } catch (se: SecurityException) {
            "Error: not allowed to change volume for this stream (a Do Not Disturb " +
                "policy may be blocking it): ${se.message}"
        } catch (e: Exception) {
            "Error: failed to set volume: ${e.message}"
        }
    }
}
