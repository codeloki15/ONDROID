package com.locallink.pro.service.pilot

import android.util.Base64
import com.locallink.pro.data.local.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenRouterPilotReasoner(
    private val settings: SettingsPreferences,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(),
) : PilotReasoner {
    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun nextAction(
        task: String, elements: List<PilotElement>, screenshot: ByteArray?, history: List<String>,
    ): Pair<String, String> {
        val key = settings.loadOpenRouterApiKey()
        val model = settings.loadOpenRouterModel()
        val elementsJson = JSONArray().apply { elements.forEach { put(it.toJson()) } }
        val userContent = JSONArray().apply {
            put(JSONObject().put("type", "text").put(
                "text",
                "Task: $task\n\nHistory:\n${history.joinToString("\n").ifBlank { "(none)" }}\n\n" +
                    "On-screen elements:\n$elementsJson\n\nChoose ONE action.",
            ))
            if (screenshot != null) {
                val b64 = Base64.encodeToString(screenshot, Base64.NO_WRAP)
                put(JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", PilotActionSchema.SYSTEM))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject()
            .put("model", model).put("messages", messages)
            .put("tools", PilotActionSchema.toolsJson())
            .put("tool_choice", "required").put("temperature", 0.2)
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://omnipin.app").addHeader("X-Title", "OmniPin")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "ask" to """{"question":"Cloud error ${resp.code}; retry?"}"""
            val msg = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return "ask" to """{"question":"No response; retry?"}"""
            val call = msg.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
                ?: return "done" to JSONObject().put("result", msg.optString("content")).toString()
            return call.optString("name") to call.optString("arguments", "{}")
        }
    }
}
