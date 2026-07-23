package com.locallink.pro.service.pilot

import org.json.JSONObject

sealed interface PilotAction {
    data class Tap(val id: Int) : PilotAction
    data class LongPress(val id: Int) : PilotAction
    data class DoubleTap(val id: Int) : PilotAction
    data class Drag(val fromId: Int, val toId: Int) : PilotAction
    data class Type(val id: Int, val text: String) : PilotAction
    data class Clear(val id: Int) : PilotAction
    data class Swipe(val direction: String) : PilotAction   // up | down | left | right
    data class Scroll(val direction: String) : PilotAction  // up | down
    data class LaunchApp(val query: String) : PilotAction    // app name or package
    object Back : PilotAction
    object Home : PilotAction
    object Recents : PilotAction
    object Notifications : PilotAction
    object QuickSettings : PilotAction
    data class Wait(val ms: Long) : PilotAction
    data class Done(val result: String) : PilotAction
    data class Ask(val question: String) : PilotAction
    data class Invalid(val reason: String) : PilotAction
}

/**
 * Scroll semantics → finger-swipe mapping. "Scroll down" means reveal content BELOW,
 * which requires the finger to drag UP (content moves up). Same inversion horizontally.
 */
object ScrollMap {
    fun toSwipe(dir: String): String = when (dir) {
        "down" -> "up"
        "up" -> "down"
        "left" -> "right"
        "right" -> "left"
        else -> dir
    }
}

object PilotActionParser {
    /** Every action the model may emit. Kept in sync with [PilotActionSchema.toolsJson]. */
    val ALLOWED = setOf(
        "tap", "long_press", "double_tap", "drag", "type", "clear",
        "swipe", "scroll", "launch_app",
        "back", "home", "recents", "notifications", "quick_settings",
        "wait", "done", "ask",
    )

    private val DIRECTIONS = setOf("up", "down", "left", "right")

    private fun needId(args: JSONObject, action: String, build: (Int) -> PilotAction): PilotAction =
        if (args.has("id")) build(args.getInt("id"))
        else PilotAction.Invalid("$action requires an integer 'id'")

    fun parse(name: String, argsJson: String): PilotAction {
        if (name !in ALLOWED) {
            return PilotAction.Invalid(
                "'$name' is not a callable action. Allowed: ${ALLOWED.joinToString(", ")}."
            )
        }
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return PilotAction.Invalid("arguments for '$name' were not valid JSON")
        }
        return when (name) {
            "tap" -> needId(args, "tap", PilotAction::Tap)
            "long_press" -> needId(args, "long_press", PilotAction::LongPress)
            "double_tap" -> needId(args, "double_tap", PilotAction::DoubleTap)
            "clear" -> needId(args, "clear", PilotAction::Clear)
            "drag" -> if (args.has("from_id") && args.has("to_id"))
                PilotAction.Drag(args.getInt("from_id"), args.getInt("to_id"))
            else PilotAction.Invalid("drag requires 'from_id' and 'to_id'")
            "type" -> when {
                !args.has("id") -> PilotAction.Invalid("type requires an integer 'id'")
                !args.has("text") -> PilotAction.Invalid("type requires a 'text' string")
                else -> PilotAction.Type(args.getInt("id"), args.getString("text"))
            }
            "swipe" -> args.optString("direction").lowercase().let {
                if (it in DIRECTIONS) PilotAction.Swipe(it)
                else PilotAction.Invalid("swipe requires 'direction' of up/down/left/right")
            }
            "scroll" -> args.optString("direction").lowercase().let {
                if (it == "up" || it == "down") PilotAction.Scroll(it)
                else PilotAction.Invalid("scroll requires 'direction' of up/down")
            }
            "launch_app" -> args.optString("app").ifBlank { args.optString("query") }.let {
                if (it.isNotBlank()) PilotAction.LaunchApp(it)
                else PilotAction.Invalid("launch_app requires an 'app' name")
            }
            "back" -> PilotAction.Back
            "home" -> PilotAction.Home
            "recents" -> PilotAction.Recents
            "notifications" -> PilotAction.Notifications
            "quick_settings" -> PilotAction.QuickSettings
            "wait" -> PilotAction.Wait(args.optLong("ms", 500L).coerceIn(100L, 10_000L))
            "done" -> PilotAction.Done(args.optString("result"))
            "ask" -> if (args.optString("question").isNotBlank()) PilotAction.Ask(args.getString("question"))
                     else PilotAction.Invalid("ask requires a non-empty 'question'")
            else -> PilotAction.Invalid("unhandled action '$name'")
        }
    }
}
