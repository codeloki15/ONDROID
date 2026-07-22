package com.locallink.pro.service.pilot

import com.locallink.pro.data.local.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun interface PlanSource {
    /** @param context prior state (completed todos, current screen) for re-planning; "" at start. */
    suspend fun plan(task: String, context: String): Plan
}

const val PLANNER_SYSTEM = """
You are the planner for a phone assistant. Break the user's request into an ordered list of todos.
For EACH todo choose a channel:
- "chat": answerable in text, no external action (facts, math, explanations).
- "composio": acts in a connected cloud app via API (send/read email, post Slack, etc.).
- "pilot": requires operating the phone's on-screen UI (change a setting, use an app with no API,
  anything visual/local on the device).
Mark "needs_input": true with a short "input_reason" for any todo needing a secret, a choice only
the user can make, confirmation of a consequential/irreversible action, or info not on the device
(passwords, "which contact?", confirm a payment).
Respond with ONLY JSON: {"todos":[{"text":"..","channel":"chat|composio|pilot","needs_input":false,"input_reason":""}]}
Keep it minimal — as few todos as truly needed.
"""

class OpenRouterPlanner(
    private val settings: SettingsPreferences,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) : PlanSource {
    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun plan(task: String, context: String): Plan {
        val key = settings.loadOpenRouterApiKey()
        val model = settings.loadOpenRouterModel()
        val fallback = Plan(listOf(Todo(task, Channel.CHAT, false, null)))
        if (key.isBlank()) return fallback
        val user = if (context.isBlank()) "Request: $task"
                   else "Request: $task\n\nProgress so far / current state:\n$context\n\nReplan the REMAINING todos."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", PLANNER_SYSTEM))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.3)
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://omnipin.app").addHeader("X-Title", "OmniPin")
            .post(body.toString().toRequestBody(json)).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return fallback
                val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                val parsed = PlanJson.parse(extractJson(content))
                if (parsed.todos.isEmpty()) fallback else parsed
            }
        }.getOrDefault(fallback)
    }

    /** Pull the first {...} block out of a possibly fenced/markdown reply. */
    private fun extractJson(s: String): String {
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }
}
