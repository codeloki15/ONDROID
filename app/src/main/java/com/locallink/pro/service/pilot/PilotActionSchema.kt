package com.locallink.pro.service.pilot

import org.json.JSONArray
import org.json.JSONObject

object PilotActionSchema {
    val SYSTEM = """
        You are Omni Pilot. You control the phone by choosing ONE action per step from the
        current screen. You are given: (1) the user's task, (2) a JSON list of on-screen
        elements each with a numeric "id", and (3) a screenshot. Combine both senses: the
        element list gives you ids/text/bounds; the screenshot gives you layout the list
        can't. Refer to elements ONLY by their "id" — never invent coordinates or ids not
        in the list. Emit exactly ONE action per step. When the task is achieved, call
        done(result). If you are unsure which element or the screen is unexpected, call
        ask(question) instead of guessing.
    """.trimIndent()

    private fun fn(name: String, description: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", name).put("description", description).put("parameters", params),
        )

    private fun obj(props: JSONObject, required: List<String>): JSONObject =
        JSONObject().put("type", "object").put("properties", props)
            .put("required", JSONArray(required))

    fun toolsJson(): JSONArray {
        val tap = fn(
            "tap", "Tap the on-screen element with the given id.",
            obj(JSONObject().put("id", JSONObject().put("type", "integer")
                .put("description", "id of the element to tap")), listOf("id")),
        )
        val done = fn(
            "done", "The task is complete. Provide the result/answer for the user.",
            obj(JSONObject().put("result", JSONObject().put("type", "string")), listOf("result")),
        )
        val ask = fn(
            "ask", "Pause and ask the user a clarifying question.",
            obj(JSONObject().put("question", JSONObject().put("type", "string")), listOf("question")),
        )
        return JSONArray().put(tap).put(done).put(ask)
    }
}
