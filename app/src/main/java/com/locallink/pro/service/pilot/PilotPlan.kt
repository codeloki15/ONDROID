package com.locallink.pro.service.pilot

import org.json.JSONObject

enum class Channel { CHAT, COMPOSIO, PILOT }

data class Todo(
    val text: String,
    val channel: Channel,
    val needsInput: Boolean,
    val inputReason: String?,
)

data class Plan(val todos: List<Todo>)

object PlanJson {
    fun channelOf(s: String): Channel = when (s.trim().lowercase()) {
        "pilot" -> Channel.PILOT
        // Composio channel disabled — the assistant works only via the mobile screen (pilot).
        // Any lingering "composio" from the model is routed to pilot (do it on-screen instead).
        "composio" -> Channel.PILOT
        else -> Channel.CHAT
    }

    fun parse(json: String): Plan {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return Plan(emptyList())
        val arr = obj.optJSONArray("todos") ?: return Plan(emptyList())
        val todos = ArrayList<Todo>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val text = t.optString("text").trim()
            if (text.isEmpty()) continue
            todos.add(
                Todo(
                    text = text,
                    channel = channelOf(t.optString("channel")),
                    needsInput = t.optBoolean("needs_input", false),
                    inputReason = t.optString("input_reason").takeIf { it.isNotBlank() },
                )
            )
        }
        return Plan(todos)
    }
}
