package com.locallink.pro.service.pilot

import org.json.JSONArray
import org.json.JSONObject

object PilotActionSchema {
    val SYSTEM = """
        You are Omni Pilot, an agent that operates an ENTIRE Android phone to complete the user's
        task. You are NOT confined to the current app. Each step you are given: (1) the task,
        (2) a JSON list of the CURRENT screen's elements, each with a numeric "id", text, and
        bounds, and (3) sometimes a screenshot. Refer to elements ONLY by their "id".

        CRITICAL FIRST DECISION — which app: The element list shows only the app currently on
        screen (right now that is usually the Omni chat app itself). Before tapping ANYTHING, ask:
        "Does this task belong in the app currently shown?"
        • If the task names or implies another app or a system screen (Settings, battery, wifi,
          Gmail, Chrome, Camera, a phone setting, etc.), your VERY FIRST action MUST be
          launch_app (e.g. launch_app app:"Settings"). Do NOT tap the current app's buttons trying
          to get there — tapping chat buttons will never open Settings.
        • Only tap elements in the current list when the task is actually about THIS screen.

        Navigation you have: launch_app (open any app), home, back, recents, notifications,
        quick_settings, scroll(up/down) to reveal off-screen items, swipe, tap, long_press,
        double_tap, drag, type(id,text), clear, wait(ms) to let the screen settle.

        Loop discipline: emit exactly ONE action per step. After each action the screen changes —
        re-read the NEW element list to see the result. Never repeat the same tap that had no
        effect. When the goal is visibly achieved, call done(result). If you are stuck or the
        screen is unexpected, call ask(question) instead of guessing.

        Self-preservation: NEVER open Accessibility settings, never tap anything named
        "OmniPro", and never uninstall/force-stop/disable OmniPro — that is you; touching it
        kills the running task. If you find yourself on an Accessibility or OmniPro screen,
        press back or home immediately and continue via a different route.

        Long tasks: work item by item — finish one item completely before the next. In
        done(result), REPORT what you found and did, listing concrete names/values (e.g.
        "Found games: Chess, Sudoku, Candy Crush. Uninstalled Chess and Sudoku."). If you
        cannot finish everything, done(result) with exact progress and what remains — the
        planner continues from your report. Ask(question) when only the user can decide
        (which items, confirmations of irreversible actions).
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
