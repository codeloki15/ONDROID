package com.locallink.pro.service.llm

import android.util.Log
import com.locallink.pro.data.local.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One model option for the in-app picker. */
data class OpenRouterModel(
    val id: String,
    val name: String,
    val free: Boolean,
    val toolCapable: Boolean,
    val contextLength: Int,
)

/**
 * Cloud brain via OpenRouter (OpenAI-compatible). Runs the chat + tool-calling loop using
 * the user's selected model, executing tool calls through [ToolRegistry] (the 23 on-device
 * handlers). Emits [AgentEvent]s so it reuses the chat UI wiring. Also lists models for the picker.
 */
@Singleton
class OpenRouterClient @Inject constructor(
    private val router: ToolRouter,
    private val settings: SettingsPreferences,
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE = "https://openrouter.ai/api/v1"
        private const val CHAT_URL = "$BASE/chat/completions"
        private const val MODELS_URL = "$BASE/models"
        private const val MAX_HOPS = 5
        private const val REFERER = "https://omnipin.app"
        private const val TITLE = "OmniPin"
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

    suspend fun hasKey(): Boolean = settings.loadOpenRouterApiKey().isNotBlank()

    private fun authed(builder: Request.Builder, key: String): Request.Builder =
        builder.addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", REFERER)
            .addHeader("X-Title", TITLE)

    /** Fetch the model catalog (filtered + flagged) for the in-app picker. */
    suspend fun fetchModels(): Result<List<OpenRouterModel>> = withContext(Dispatchers.IO) {
        val key = settings.loadOpenRouterApiKey()
        try {
            val reqB = Request.Builder().url(MODELS_URL).get()
            if (key.isNotBlank()) authed(reqB, key)
            http.newCall(reqB.build()).execute().use { resp ->
                val text = resp.body?.string() ?: return@withContext Result.failure(Exception("empty"))
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
                val out = ArrayList<OpenRouterModel>(data.length())
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val id = m.optString("id")
                    if (id.isBlank()) continue
                    val sp = m.optJSONArray("supported_parameters")
                    val toolCap = sp != null && (0 until sp.length()).any { sp.optString(it) == "tools" }
                    val pricing = m.optJSONObject("pricing")
                    val free = id.endsWith(":free") ||
                        (pricing?.optString("prompt") == "0" && pricing.optString("completion") == "0")
                    out += OpenRouterModel(
                        id = id,
                        name = m.optString("name", id),
                        free = free,
                        toolCapable = toolCap,
                        contextLength = m.optInt("context_length", 0),
                    )
                }
                Result.success(out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchModels failed", e)
            Result.failure(e)
        }
    }

    fun run(
        history: List<Pair<String, String>>,
        userText: String,
        confirm: suspend (name: String, argsJson: String) -> Boolean,
    ): Flow<AgentEvent> = flow {
        val key = settings.loadOpenRouterApiKey()
        if (key.isBlank()) {
            emit(AgentEvent.Final("No OpenRouter API key set. Add one in Settings → AI Model."))
            return@flow
        }
        val model = settings.loadOpenRouterModel()

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM))
        for ((role, text) in history) {
            val r = if (role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", r).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val tools = router.schemas()

        var hop = 0
        while (hop < MAX_HOPS) {
            hop++
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("temperature", 0.6)

            val respMsg = postChat(key, body) ?: run {
                emit(AgentEvent.Final("Network error talking to OpenRouter. Check connection and API key."))
                return@flow
            }

            val toolCalls = respMsg.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                val content = respMsg.optString("content").ifBlank { "(no reply)" }
                emit(AgentEvent.Token(content))
                emit(AgentEvent.Final(content))
                return@flow
            }

            messages.put(respMsg)
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val id = tc.optString("id")
                val fn = tc.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                val args = try { JSONObject(fn.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }

                val readOnly = !router.requiresConfirmation(name)
                emit(AgentEvent.ToolCall(id, name, args.toString(), readOnly))

                val result = if (readOnly || confirm(name, args.toString())) {
                    router.execute(name, args)
                } else {
                    JSONObject().put("error", "User declined to run $name").toString()
                }
                emit(AgentEvent.ToolResult(id, name, result, !result.contains("\"error\"")))

                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", id)
                        .put("name", name).put("content", result)
                )
            }
        }

        Log.w(TAG, "Hit MAX_HOPS")
        emit(AgentEvent.Final("(Stopped after $MAX_HOPS tool steps.)"))
    }.flowOn(Dispatchers.IO)

    private fun postChat(key: String, body: JSONObject): JSONObject? {
        return try {
            val req = authed(Request.Builder().url(CHAT_URL), key)
                .post(body.toString().toRequestBody(json))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    Log.e(TAG, "OpenRouter ${resp.code}: ${text.take(300)}")
                    return JSONObject().put("content", "OpenRouter error ${resp.code}: ${shortErr(text)}")
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
