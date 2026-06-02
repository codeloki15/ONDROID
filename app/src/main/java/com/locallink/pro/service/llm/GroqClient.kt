package com.locallink.pro.service.llm

import android.util.Log
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.llm.tools.ToolCall
import com.locallink.pro.service.llm.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud brain: Groq's openai/gpt-oss-120b. Runs the full chat + tool-calling loop using
 * the OpenAI-compatible Chat Completions API, executing tool calls via [ToolRegistry]
 * (the same 23 on-device handlers). Emits [AgentEvent]s so it reuses the chat UI wiring.
 */
@Singleton
class GroqClient @Inject constructor(
    private val registry: ToolRegistry,
    private val settings: SettingsPreferences,
) {
    companion object {
        private const val TAG = "GroqClient"
        private const val URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "openai/gpt-oss-120b"
        private const val MAX_HOPS = 5
        private const val SYSTEM =
            "You are Omni, a helpful assistant running on the user's Android phone. " +
            "You can perform on-device actions by calling the provided tools (set timers/alarms, " +
            "flashlight, calls, contacts, calendar, notes, etc.). Prefer calling a tool when the " +
            "user asks for a device action. Keep replies concise."
    }

    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun hasKey(): Boolean = settings.loadGroqApiKey().isNotBlank()

    /**
     * @param history prior (role, text) pairs (role in {"user","assistant"}).
     * @param confirm invoked for non-read-only tool calls; true to execute.
     */
    fun run(
        history: List<Pair<String, String>>,
        userText: String,
        confirm: suspend (name: String, argsJson: String) -> Boolean,
    ): Flow<AgentEvent> = flow {
        val key = settings.loadGroqApiKey()
        if (key.isBlank()) {
            emit(AgentEvent.Final("No Groq API key set. Add one in Settings → Groq API key."))
            return@flow
        }

        // Build the OpenAI messages array.
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM))
        for ((role, text) in history) {
            val r = if (role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", r).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val tools = registry.openAiToolsArray()

        var hop = 0
        while (hop < MAX_HOPS) {
            hop++
            val body = JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("temperature", 0.6)
                .put("reasoning_effort", "low")
                .put("max_completion_tokens", 1024)

            val respMsg = postChat(key, body) ?: run {
                emit(AgentEvent.Final("Network error talking to Groq. Check connection and API key."))
                return@flow
            }

            val toolCalls = respMsg.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                val content = respMsg.optString("content").ifBlank { "(no reply)" }
                emit(AgentEvent.Token(content))
                emit(AgentEvent.Final(content))
                return@flow
            }

            // Echo the assistant tool-call message verbatim, then append one tool result per call.
            messages.put(respMsg)
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val id = tc.optString("id")
                val fn = tc.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                val argsStr = fn.optString("arguments", "{}")
                val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }

                val readOnly = !registry.requiresConfirmation(name)
                emit(AgentEvent.ToolCall(id, name, args.toString(), readOnly))

                val result = if (readOnly || confirm(name, args.toString())) {
                    registry.execute(ToolCall(name, args))
                } else {
                    JSONObject().put("error", "User declined to run $name").toString()
                }
                emit(AgentEvent.ToolResult(id, name, result, !result.contains("\"error\"")))

                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", id)
                        .put("name", name).put("content", result)
                )
            }
            // loop: re-POST with tool results so the model produces the final reply
        }

        Log.w(TAG, "Hit MAX_HOPS")
        emit(AgentEvent.Final("(Stopped after $MAX_HOPS tool steps.)"))
    }.flowOn(Dispatchers.IO)

    /** POST one chat completion; return choices[0].message JSONObject, or null on failure. */
    private fun postChat(key: String, body: JSONObject): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(URL)
                .addHeader("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody(json))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Groq ${resp.code}: ${text.take(300)}")
                    return JSONObject().put("content", "Groq error ${resp.code}: ${shortErr(text)}")
                }
                JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "postChat failed", e)
            null
        }
    }

    private fun shortErr(text: String): String = try {
        JSONObject(text).optJSONObject("error")?.optString("message")?.take(160) ?: text.take(160)
    } catch (_: Exception) { text.take(160) }
}
