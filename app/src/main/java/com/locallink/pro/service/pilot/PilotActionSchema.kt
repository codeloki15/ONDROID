package com.locallink.pro.service.pilot

import org.json.JSONArray
import org.json.JSONObject

object PilotActionSchema {
    val SYSTEM = """
        You are Omni Pilot, an agent that operates an Android phone to complete the user's task.
        Each step you are given: (1) the task, (2) a JSON list of on-screen elements each with a
        numeric "id", text, and bounds, and (3) sometimes a screenshot. Refer to elements ONLY by
        their "id" — never invent ids or raw coordinates.

        You control the WHOLE phone, not just the current app. To do a task in another app, call
        launch_app first (e.g. launch_app app:"Settings"), then navigate with tap/scroll/swipe.
        Use back or home to leave a screen. Use scroll(direction) to reveal off-screen elements
        before deciding a target isn't there. Use type(id,text) to fill a text field, and
        wait(ms) after an action that needs the screen to settle.

        Emit exactly ONE action per step. Re-read the new element list each step to see the result
        of your last action. When the task is achieved, call done(result). If the screen is
        unexpected or you truly cannot proceed, call ask(question) — do not guess blindly or
        repeat the same failing tap.
    """.trimIndent()

    private fun fn(name: String, description: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", name).put("description", description).put("parameters", params),
        )

    private fun obj(props: JSONObject, required: List<String>): JSONObject =
        JSONObject().put("type", "object").put("properties", props)
            .put("required", JSONArray(required))

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    private fun enumStr(desc: String, values: List<String>) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values))

    fun toolsJson(): JSONArray {
        val tap = fn(
            "tap", "Tap the on-screen element with the given id.",
            obj(JSONObject().put("id", int("id of the element to tap")), listOf("id")),
        )
        val longPress = fn(
            "long_press", "Long-press an element (opens context menus).",
            obj(JSONObject().put("id", int("id of the element")), listOf("id")),
        )
        val doubleTap = fn(
            "double_tap", "Double-tap an element.",
            obj(JSONObject().put("id", int("id of the element")), listOf("id")),
        )
        val drag = fn(
            "drag", "Drag one element onto another (press, move, release).",
            obj(JSONObject().put("from_id", int("id to drag from"))
                .put("to_id", int("id to drop onto")), listOf("from_id", "to_id")),
        )
        val type = fn(
            "type", "Type text into the editable element with the given id.",
            obj(JSONObject().put("id", int("id of the text field"))
                .put("text", str("text to type")), listOf("id", "text")),
        )
        val clear = fn(
            "clear", "Clear all text from the editable element with the given id.",
            obj(JSONObject().put("id", int("id of the text field")), listOf("id")),
        )
        val swipe = fn(
            "swipe", "Swipe the screen in a direction (to reveal content or change pages).",
            obj(JSONObject().put("direction",
                enumStr("swipe direction", listOf("up", "down", "left", "right"))),
                listOf("direction")),
        )
        val scroll = fn(
            "scroll", "Scroll the current list/screen up or down to reveal more elements.",
            obj(JSONObject().put("direction", enumStr("scroll direction", listOf("up", "down"))),
                listOf("direction")),
        )
        val launchApp = fn(
            "launch_app", "Open an app by name (e.g. 'Settings', 'Gmail'). Use this to leave the " +
                "current app and start a task elsewhere.",
            obj(JSONObject().put("app", str("app name to launch")), listOf("app")),
        )
        val back = fn("back", "Press the system Back button.", obj(JSONObject(), emptyList()))
        val home = fn("home", "Go to the home screen.", obj(JSONObject(), emptyList()))
        val recents = fn("recents", "Open the recent-apps switcher.", obj(JSONObject(), emptyList()))
        val notifications = fn("notifications", "Pull down the notification shade.", obj(JSONObject(), emptyList()))
        val quickSettings = fn("quick_settings", "Pull down the quick-settings panel.", obj(JSONObject(), emptyList()))
        val wait = fn(
            "wait", "Wait for the screen to settle after an action.",
            obj(JSONObject().put("ms", int("milliseconds to wait (100-10000)")), emptyList()),
        )
        val done = fn(
            "done", "The task is complete. Provide the result/answer for the user.",
            obj(JSONObject().put("result", str("result for the user")), listOf("result")),
        )
        val ask = fn(
            "ask", "Pause and ask the user a clarifying question.",
            obj(JSONObject().put("question", str("question for the user")), listOf("question")),
        )
        return JSONArray()
            .put(tap).put(longPress).put(doubleTap).put(drag).put(type).put(clear)
            .put(swipe).put(scroll).put(launchApp)
            .put(back).put(home).put(recents).put(notifications).put(quickSettings)
            .put(wait).put(done).put(ask)
    }
}
