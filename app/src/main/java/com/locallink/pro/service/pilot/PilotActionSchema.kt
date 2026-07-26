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
        double_tap, drag, type(id,text), clear, press_enter(id), wait(ms) to let the screen settle.
        To RUN a search: type(id,text) then press_enter(id) — typing only fills the box.
        To reach something off-screen: find(text) scrolls to it — don't guess repeated swipes.
        If press_enter reports it isn't available, tap the app's own search/submit control.

        Typing: always use type(id,text) — never tap keyboard keys one at a time. It REPLACES the
        whole field, so there is no need to clear first. After typing into a search box an
        auto-complete list usually appears: either tap the entry that matches what you meant, or
        press_enter to search exactly what you typed. Do not leave it open and tap elsewhere.

        Text already on screen: long_press it to raise the selection bar (copy / select all /
        paste), then tap the option you want. To empty a field use clear(id) rather than deleting
        characters one at a time.

        Loop discipline: emit exactly ONE action per step. After each action the screen changes —
        re-read the NEW element list to see the result. Never repeat the same tap that had no
        effect. Most tasks have several routes — take the easiest one. Retrying something once is
        fine; if the history shows it has already failed twice, SWITCH approach — a different
        element, a different screen, or a different app — instead of trying it a third time.
        When the goal is visibly achieved, call done(result). If you are stuck or the screen is
        unexpected, call ask(question) instead of guessing.

        Elements marked "disabled": true do NOT respond to taps. Something must activate them
        first — usually scrolling the page content to the bottom (consent/onboarding screens),
        filling a required field, or selecting an item. Do that FIRST, then tap.

        RECOGNIZE SUCCESS: before acting, check whether the goal is ALREADY achieved on this
        screen — e.g. the requested song/video is playing, the setting is already toggled, the
        message was sent. If so call done(result) IMMEDIATELY. Never tap additional search
        results or repeat an action "to be sure" once playback/​the goal state has started.

        Self-preservation: NEVER open Accessibility settings, never tap anything named
        "OmniPro", and never uninstall/force-stop/disable OmniPro — that is you; touching it
        kills the running task. If you find yourself on an Accessibility or OmniPro screen,
        press back or home immediately and continue via a different route.

        ANSWER THE QUESTION: when the task asks something ("what does X cost", "when is my next
        meeting"), reaching the screen that shows it is only half the job. The value must be IN
        done(result), quoted exactly as it appears on screen — the user reads your reply, not the
        phone. Ending a question with "Done" or "Found it" is a failed task.

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
            "type",
            "Set the editable element's text. This REPLACES everything already in the field — " +
                "it is not an insert. To add to existing text, include the existing text in what " +
                "you send. To empty a field, use clear.",
            obj(JSONObject().put("id", int("id of the text field"))
                .put("text", str("the field's full new contents (replaces what is there)")),
                listOf("id", "text")),
        )
        val clear = fn(
            "clear",
            "Empty the editable element (select-all then delete). Only needed when the field " +
                "should end up blank — type already replaces the contents.",
            obj(JSONObject().put("id", int("id of the text field")), listOf("id")),
        )
        val pressEnter = fn(
            "press_enter",
            "Press the keyboard's action key (Search / Go / Send) on a text field you just " +
                "typed into. Use this to RUN a search when the app has no visible search button " +
                "— typing alone only fills the box, it does not submit.",
            obj(JSONObject().put("id", int("id of the text field to submit")), listOf("id")),
        )
        val find = fn(
            "find",
            "Scroll until something whose text or description contains the given text is on " +
                "screen, then stop. Use this instead of guessing repeated scrolls when what you " +
                "need isn't in the element list — including when the list says elements were omitted.",
            obj(JSONObject().put("text", str("text to scroll to, e.g. a product name or a setting")),
                listOf("text")),
        )
        val swipe = fn(
            "swipe", "Swipe the screen in a direction (to reveal content or change pages).",
            obj(JSONObject().put("direction",
                enumStr("swipe direction", listOf("up", "down", "left", "right"))),
                listOf("direction")),
        )
        val scroll = fn(
            "scroll",
            "Scroll the list to reveal more elements. Direction is where the CONTENT moves you: " +
                "\"down\" reveals what is below (further down the list), \"up\" goes back toward " +
                "the top. If one direction doesn't reveal what you expected, try the other. " +
                "Prefer find(text) when you know what you're looking for.",
            obj(JSONObject().put("direction",
                enumStr("\"down\" to see further down the list, \"up\" to go back up",
                    listOf("up", "down"))),
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
            .put(tap).put(longPress).put(doubleTap).put(drag).put(type).put(clear).put(pressEnter).put(find)
            .put(swipe).put(scroll).put(launchApp)
            .put(back).put(home).put(recents).put(notifications).put(quickSettings)
            .put(wait).put(done).put(ask)
    }
}
