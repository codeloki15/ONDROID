package com.locallink.pro.service.pilot

import org.json.JSONObject

sealed interface PilotAction {
    data class Tap(val id: Int) : PilotAction
    data class Done(val result: String) : PilotAction
    data class Ask(val question: String) : PilotAction
    data class Invalid(val reason: String) : PilotAction
}

object PilotActionParser {
    /** Actions the model may emit in the thin slice. */
    val ALLOWED = setOf("tap", "done", "ask")

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
            "tap" -> if (args.has("id")) PilotAction.Tap(args.getInt("id"))
                     else PilotAction.Invalid("tap requires an integer 'id'")
            "done" -> PilotAction.Done(args.optString("result"))
            "ask" -> if (args.optString("question").isNotBlank()) PilotAction.Ask(args.getString("question"))
                     else PilotAction.Invalid("ask requires a non-empty 'question'")
            else -> PilotAction.Invalid("unhandled action '$name'")
        }
    }
}
