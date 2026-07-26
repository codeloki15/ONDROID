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
        "Read or change the device volume. Give \"percent\" for an exact level, or " +
            "\"direction\" to move it relative to where it is now (up/down/mute/unmute). " +
            "Give NEITHER to just read the current level without changing anything. " +
            "Streams: music (media), ring, notification, alarm — defaults to music."

    // One tool, three behaviours, rather than three tools. Every registered tool costs tokens on
    // every turn, and "turn it up a bit" and "how loud is it?" do not each deserve their own
    // entry in a prompt we already measured at ~4.7k tokens per step.
    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "percent": {
              "type": "integer",
              "description": "Exact target volume, 0-100. Omit when using direction.",
              "minimum": 0,
              "maximum": 100
            },
            "direction": {
              "type": "string",
              "description": "Move the volume relative to its current level. Use this for \"turn it up/down\" — you do NOT need to know the current level first.",
              "enum": ["up", "down", "mute", "unmute"]
            },
            "step": {
              "type": "integer",
              "description": "How far up/down moves, in percent. Default 10.",
              "minimum": 1,
              "maximum": 100,
              "default": 10
            },
            "stream": {
              "type": "string",
              "description": "Which audio stream to read or change",
              "enum": ["music", "ring", "notification", "alarm"],
              "default": "music"
            }
          }
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        return try {
            val streamName = args.optString("stream", "music")
                .trim().lowercase().ifEmpty { "music" }
            val streamType = streamOf(streamName)
                ?: return "Error: unsupported stream '$streamName'. " +
                    "Use one of: music, ring, notification, alarm."

            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "Error: AudioManager unavailable on this device."
            val max = audio.getStreamMaxVolume(streamType)
            if (max <= 0) {
                return "Error: stream '$streamName' reports no adjustable volume range."
            }

            val before = audio.getStreamVolume(streamType)
            val direction = args.optString("direction").trim().lowercase().ifEmpty { null }
            // Tolerate a percent arriving as a string or a double — models are inconsistent about
            // JSON number types, and this used to be the difference between working and an error.
            val percent = args.opt("percent")?.let { raw ->
                when (raw) {
                    is Number -> raw.toInt()
                    is String -> raw.trim().toDoubleOrNull()?.toInt()
                    else -> null
                }
            }

            when {
                direction != null -> {
                    val step = stepIndex(args.optInt("step", DEFAULT_STEP_PERCENT), max)
                    when (direction) {
                        "up" -> audio.setStreamVolume(streamType, (before + step).coerceAtMost(max), 0)
                        "down" -> audio.setStreamVolume(streamType, (before - step).coerceAtLeast(0), 0)
                        // ADJUST_MUTE over setStreamVolume(0): it remembers the level so unmute
                        // restores it, where zeroing loses where it was.
                        "mute" -> audio.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                        "unmute" -> audio.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                        else -> return "Error: unsupported direction '$direction'. " +
                            "Use up, down, mute or unmute."
                    }
                }
                percent != null -> audio.setStreamVolume(streamType, indexFor(percent, max), 0)
                // Neither given: a read. Reporting instead of erroring is what lets the model
                // answer "how loud is it?" and decide a relative move for itself.
                else -> {}
            }

            val after = audio.getStreamVolume(streamType)
            val nowPct = percentOf(after, max)
            val changed = direction != null || percent != null
            JSONObject().apply {
                put("ok", true)
                put("stream", streamName)
                put("percent", nowPct)
                put("volume_index", after)
                put("max_index", max)
                put("muted", after == 0)
                if (changed) {
                    put("was_percent", percentOf(before, max))
                    put("changed", after != before)
                }
                // The sentence the user reads when this runs on the one-shot fast path, where
                // there is no second model turn to phrase the payload. See humanReply().
                put(
                    "summary",
                    when {
                        after == 0 && changed -> "Muted the $streamName volume."
                        !changed -> "The $streamName volume is at $nowPct%."
                        after == before -> "The $streamName volume was already at $nowPct%."
                        else -> "Set the $streamName volume to $nowPct% " +
                            "(was ${percentOf(before, max)}%)."
                    },
                )
            }.toString()
        } catch (se: SecurityException) {
            // Ring and notification volumes are policy-controlled while Do Not Disturb is on.
            "Error: not allowed to change the '${args.optString("stream", "music")}' volume — " +
                "Do Not Disturb is probably holding it: ${se.message}"
        } catch (e: Exception) {
            "Error: failed to set volume: ${e.message}"
        }
    }

    companion object {
        const val DEFAULT_STEP_PERCENT = 10

        fun streamOf(name: String): Int? = when (name) {
            "music", "media" -> AudioManager.STREAM_MUSIC
            "ring", "ringer", "ringtone" -> AudioManager.STREAM_RING
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            else -> null
        }

        /** Percent → device index, rounded to the nearest real step. */
        fun indexFor(percent: Int, max: Int): Int =
            Math.round(percent.coerceIn(0, 100) / 100.0 * max).toInt().coerceIn(0, max)

        /**
         * How many index steps a relative move covers — never fewer than one.
         *
         * Streams commonly have 7 or 15 steps, so a 10% step rounds to 0.7 and then to zero. A
         * "turn it up" that reports success and changes nothing is worse than an error.
         */
        fun stepIndex(stepPercent: Int, max: Int): Int =
            Math.round(stepPercent.coerceIn(1, 100) / 100.0 * max).toInt().coerceAtLeast(1)

        fun percentOf(index: Int, max: Int): Int =
            if (max <= 0) 0 else Math.round(index / max.toDouble() * 100.0).toInt()
    }
}
