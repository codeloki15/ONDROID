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
import java.util.UUID
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
 * Thrown when the cloud model can't serve the request (rate limit, quota, auth, server
 * or network error). [ChatRepository] catches this and falls back to the on-device model
 * so the user is never blocked by OpenRouter limits.
 */
class OpenRouterUnavailable(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/**
 * Cloud brain via OpenRouter (OpenAI-compatible). Runs the chat + tool-calling loop using
 * the user's selected model, executing tool calls through [ToolRouter] — which exposes
 * **Composio cloud tools only** (the on-device tools are not advertised or run). Emits
 * [AgentEvent]s so it reuses the chat UI wiring. Also lists models for the picker.
 */
@Singleton
class OpenRouterClient @Inject constructor(
    private val mcp: ComposioMcpClient,
    private val settings: SettingsPreferences,
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE = "https://openrouter.ai/api/v1"
        private const val CHAT_URL = "$BASE/chat/completions"
        private const val MODELS_URL = "$BASE/models"
        // Composio's Tool Router flow is inherently multi-step (SEARCH_TOOLS → GET_TOOL_SCHEMAS →
        // MULTI_EXECUTE_TOOL, often repeated as the model refines). A cap of 5 killed real tasks
        // mid-flight ("Stopped after 5 tool steps"). 16 gives room to finish while still bounding
        // runaway loops. Each hop that only errored (no new info) is cheap; the real guard is
        // whether the model keeps making progress, which the corrective error feedback encourages.
        private const val MAX_HOPS = 16
        private const val REFERER = "https://omnipin.app"
        private const val TITLE = "OmniPin"
        // The model may ONLY call the four Composio meta-tools by their exact UPPERCASE names.
        // Calling app tools directly (e.g. COMPOSIO_SEARCH_WEB, LINKEDIN_GET_PERSON) fails with
        // MCP -32602 "Tool not found" — app tools are executed *through* COMPOSIO_MULTI_EXECUTE_TOOL,
        // never invoked as top-level tool calls. This constraint stops the invented-tool errors.
        private const val SYSTEM =
            "You are Omni, a helpful AI assistant. You act in the user's connected cloud apps " +
            "(Gmail, Slack, LinkedIn, …) ONLY through Composio's four meta-tools. You have NO " +
            "on-device phone controls (no timers, alarms, flashlight, calls, SMS, contacts).\n\n" +
            "TOOL RULES — follow exactly:\n" +
            "1. The ONLY tools you may call are: COMPOSIO_SEARCH_TOOLS, COMPOSIO_GET_TOOL_SCHEMAS, " +
            "COMPOSIO_MULTI_EXECUTE_TOOL, COMPOSIO_MANAGE_CONNECTIONS. Never call any other tool " +
            "name (e.g. COMPOSIO_SEARCH_WEB, LINKEDIN_GET_PERSON) directly — app tools run ONLY " +
            "inside COMPOSIO_MULTI_EXECUTE_TOOL's `tools` array.\n" +
            "2. Discover first: call COMPOSIO_SEARCH_TOOLS to find the right app tool(s) and a plan " +
            "before executing. Never invent tool slugs.\n" +
            "3. When executing, COMPOSIO_MULTI_EXECUTE_TOOL REQUIRES a non-empty `tools` array — " +
            "each entry a real tool slug from search results with a fully-formed `arguments` object. " +
            "Never call it with empty {} or placeholder arguments.\n" +
            "4. If a tool result says successful:false or returns an error, read the error, FIX your " +
            "arguments, and retry — do not repeat the same failing call.\n" +
            "5. If an app isn't connected, call COMPOSIO_MANAGE_CONNECTIONS with the toolkit slug.\n" +
            "If no apps are connected, answer in text and suggest connecting apps in Settings. " +
            "Keep replies concise."
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

        // Composio Tool Router meta-tools (over MCP): the model uses these to search for app
        // tools, connect apps in-chat, and execute — driving the whole flow itself.
        // metaToolSchemas() also creates the session, which populates mcp.assistivePrompt.
        val metaTools = if (mcp.isEnabled()) mcp.metaToolSchemas() else JSONArray()
        // One stable Tool Router session id for this whole run. The model previously invented a
        // fresh id every hop ("able", "bite"), so Tool Router lost state between steps. Pinning it
        // here and telling the model to reuse it keeps search→schema→execute on one session.
        val toolSessionId = "omni-" + UUID.randomUUID().toString().take(8)
        val sys = buildString {
            append(SYSTEM)
            if (metaTools.length() > 0) {
                // Composio's OWN guidance — it spells out the exact protocol (SEARCH_TOOLS first,
                // include a properly-formed `arguments` field matching the schema, never pass
                // placeholders/empty args, connect via MANAGE_CONNECTIONS, etc.). This fixed the
                // model calling COMPOSIO_MANAGE_CONNECTIONS with empty {} and skipping search.
                append("\n\n# Composio Tool Router\n")
                append(mcp.assistivePrompt.ifBlank {
                    "Call COMPOSIO_SEARCH_TOOLS first to find tools + a plan; then execute with " +
                        "COMPOSIO_MULTI_EXECUTE_TOOL. If auth is needed, call COMPOSIO_MANAGE_CONNECTIONS " +
                        "with the toolkit slug(s). Always include a properly-formed arguments object " +
                        "matching each tool's schema; never pass empty or placeholder arguments."
                })
                append("\nThe app auto-opens any connection link from COMPOSIO_MANAGE_CONNECTIONS; ")
                append("after the user authorizes, continue the task.")
                // Fix #4: one session id for the whole task so Tool Router state carries across hops.
                append("\n\nUse this exact session id for EVERY Composio call in this task, wherever ")
                append("a session/session_id field is accepted: \"$toolSessionId\". Do not invent a new one.")
            }
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", sys))
        for ((role, text) in history) {
            val r = if (role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", r).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        // Guard against a stuck model repeating the identical failing call for all MAX_HOPS.
        // Track the signature (name+args) of each tool call; if the same one repeats too many
        // times in a row, stop early rather than burning the whole (now larger) hop budget.
        var lastCallSig: String? = null
        var repeatCount = 0

        var hop = 0
        while (hop < MAX_HOPS) {
            hop++
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.6)
            if (metaTools.length() > 0) body.put("tools", metaTools).put("tool_choice", "auto")

            // Throws OpenRouterUnavailable on rate-limit/quota/auth/server/network errors,
            // which ChatRepository catches to fall back to the on-device model.
            val respMsg = postChat(key, body)

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

                // Stuck-loop guard: same tool + same args three times running → give up gracefully.
                val sig = "$name:${args}"
                if (sig == lastCallSig) repeatCount++ else { repeatCount = 0; lastCallSig = sig }
                if (repeatCount >= 2) {
                    Log.w(TAG, "Same tool call repeated; stopping to avoid a loop: $name")
                    emit(AgentEvent.Final("I hit a snag running that repeatedly and stopped. " +
                        "Could you rephrase or give me a bit more detail?"))
                    return@flow
                }

                emit(AgentEvent.ToolCall(id, name, args.toString(), true))

                // Fix #3 guard: reject invented tool names locally instead of paying a round-trip
                // to get MCP -32602. Steer the model back to the meta-tool protocol.
                if (!mcp.isMetaTool(name)) {
                    val correction = "Error: '$name' is not a callable tool. Only these meta-tools " +
                        "exist: ${ComposioMcpClient.META_TOOLS.joinToString(", ")}. To use an app " +
                        "tool, discover it via COMPOSIO_SEARCH_TOOLS then run it inside " +
                        "COMPOSIO_MULTI_EXECUTE_TOOL's `tools` array."
                    emit(AgentEvent.ToolResult(id, name, correction, false))
                    messages.put(
                        JSONObject().put("role", "tool").put("tool_call_id", id)
                            .put("name", name).put("content", correction),
                    )
                    continue
                }

                val res = mcp.callTool(name, args)
                // If Composio returned an auth link, open it so the user can connect in-flow.
                res.authUrl?.let { emit(AgentEvent.OpenAuthUrl(it)) }

                // Fix #2: MCP-level success (res.success) misses app-level failures — Composio
                // returns {"successful":false,"error":...} inside the text with isError=false. Detect
                // those so the UI marks the step failed AND the model is told to fix-and-retry.
                val failed = !res.success || looksLikeToolError(res.text)
                val content = if (failed) {
                    res.text +
                        "\n\n[The call above FAILED. Read the error, correct your arguments " +
                        "(ensure required fields like a non-empty `tools` array are present), and retry. " +
                        "Do not repeat the identical call.]"
                } else res.text
                emit(AgentEvent.ToolResult(id, name, res.text, !failed))

                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", id)
                        .put("name", name).put("content", content),
                )
            }
        }

        Log.w(TAG, "Hit MAX_HOPS")
        emit(AgentEvent.Final("(Stopped after $MAX_HOPS tool steps.)"))
    }.flowOn(Dispatchers.IO)

    /**
     * POST a chat turn. Returns the assistant message on success.
     * Throws [OpenRouterUnavailable] on rate-limit (429), quota/payment (402), auth (401/403),
     * server (5xx), or network errors — the signal for ChatRepository to fall back to local.
     */
    private fun postChat(key: String, body: JSONObject): JSONObject {
        val resp = try {
            val req = authed(Request.Builder().url(CHAT_URL), key)
                .post(body.toString().toRequestBody(json))
                .build()
            http.newCall(req).execute()
        } catch (e: Exception) {
            Log.e(TAG, "postChat network error", e)
            throw OpenRouterUnavailable("network error", e)
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                Log.e(TAG, "OpenRouter ${it.code}: ${text.take(300)}")
                val reason = when (it.code) {
                    429 -> "rate limited"
                    402 -> "out of credits"
                    401, 403 -> "auth error"
                    in 500..599 -> "server error ${it.code}"
                    else -> "HTTP ${it.code}: ${shortErr(text)}"
                }
                // Treat the recoverable classes as "fall back to local"; surface others too
                // (a 400 etc. is unlikely to differ on retry, but local is still better than nothing).
                throw OpenRouterUnavailable(reason)
            }
            return JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw OpenRouterUnavailable("empty response")
        }
    }

    /**
     * A plain, tool-free single-turn chat reply (no Composio meta-tools). Used by the planning
     * agent's "chat" channel so a simple question ("what is 7×8") returns clean text instead of
     * going through the tool-calling machinery. Returns "" on any failure.
     */
    suspend fun plainChat(prompt: String): String = withContext(Dispatchers.IO) {
        val key = settings.loadOpenRouterApiKey()
        if (key.isBlank()) return@withContext ""
        val model = settings.loadOpenRouterModel()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "You are Omni, a concise, helpful assistant. Answer directly."))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.6)
        runCatching { postChat(key, body).optString("content") }.getOrDefault("")
    }

    private fun shortErr(text: String): String = try {
        JSONObject(text).optJSONObject("error")?.optString("message")?.take(160) ?: text.take(160)
    } catch (_: Exception) { text.take(160) }

    /**
     * App-level failure detection for a Composio tool result. The MCP envelope reports success
     * (isError=false) even when the Tool Router payload itself failed, e.g.
     *   {"data":{},"error":"Invalid request ...: Validation error: Required at \"tools\"","successful":false}
     * or a transport-style "MCP error -32602: Tool ... not found" string. We flag these so the loop
     * marks the step failed and feeds a fix-and-retry hint back to the model.
     */
    private fun looksLikeToolError(text: String): Boolean {
        val t = text.trimStart()
        // JSON-RPC error text that leaked into the tool content.
        if (t.contains("MCP error -")) return true
        // Try to read the Composio result envelope.
        runCatching { JSONObject(t) }.getOrNull()?.let { obj ->
            if (obj.has("successful") && !obj.optBoolean("successful", true)) return true
            // A non-empty top-level "error" string (distinct from an "error" object we can't judge).
            val err = obj.opt("error")
            if (err is String && err.isNotBlank()) return true
        }
        return false
    }
}
