package com.locallink.pro.service.llm

import android.util.Log
import com.locallink.pro.data.local.SettingsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for Composio's **Tool Router**, which is an MCP (Model Context Protocol) server.
 *
 * A tool-router session ([ensureSession]) returns an MCP URL serving orchestration meta-tools:
 * COMPOSIO_SEARCH_TOOLS (find tools + a recommended plan), COMPOSIO_MANAGE_CONNECTIONS (returns
 * an OAuth link to connect an app in-chat), COMPOSIO_MULTI_EXECUTE_TOOL (run discovered tools),
 * etc. We speak MCP over streamable-HTTP (JSON-RPC POST; responses are SSE `data:` framed; the
 * session is encoded in the URL so no extra session header is needed).
 *
 * This replaces the old hand-rolled REST search/execute — the model now drives the whole flow
 * via these meta-tools, exactly like Composio's own agent.
 */
@Singleton
class ComposioMcpClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsPreferences,
) {
    companion object {
        private const val TAG = "ComposioMcp"
        private const val BASE = "https://backend.composio.dev"
        private const val SESSION_URL = "$BASE/api/v3/tool_router/session"
        private const val PROTOCOL = "2025-06-18"
        /** The router meta-tools we surface to the model (workbench/bash omitted by default). */
        val META_TOOLS = setOf(
            "COMPOSIO_SEARCH_TOOLS",
            "COMPOSIO_MANAGE_CONNECTIONS",
            "COMPOSIO_MULTI_EXECUTE_TOOL",
            "COMPOSIO_GET_TOOL_SCHEMAS",
        )
    }

    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // tool execution + wait-for-connection can be slow
        .build()

    private val rpcId = AtomicInteger(1)
    private val mutex = Mutex()

    @Volatile private var mcpUrl: String? = null
    @Volatile private var initialized = false
    /** Composio's official guidance for driving the meta-tools (from the session response). */
    @Volatile var assistivePrompt: String = ""
        private set

    suspend fun isEnabled(): Boolean = settings.loadComposioApiKey().isNotBlank()

    /** Drop the current session so the next call starts fresh (e.g. new chat / after errors). */
    fun reset() { mcpUrl = null; initialized = false; assistivePrompt = "" }

    /**
     * Ensure a tool-router session exists and the MCP transport is initialized. Returns the MCP
     * URL, or null if Composio isn't configured / setup failed.
     */
    private suspend fun ensureSession(): String? {
        mcpUrl?.let { if (initialized) return it }
        val key = settings.loadComposioApiKey()
        if (key.isBlank()) return null
        return mutex.withLock {
            mcpUrl?.let { if (initialized) return it }
            try {
                val userId = settings.loadComposioUserId()
                val body = JSONObject().put("user_id", userId)
                val req = Request.Builder().url(SESSION_URL)
                    .addHeader("x-api-key", key).addHeader("content-type", "application/json")
                    .post(body.toString().toRequestBody(json)).build()
                val sessText = http.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (!r.isSuccessful) { Log.e(TAG, "session HTTP ${r.code}: ${t.take(200)}"); return@withLock null }
                    t
                }
                val sessObj = JSONObject(sessText)
                val url = sessObj.optJSONObject("mcp")?.optString("url")
                    ?.takeIf { it.isNotBlank() } ?: run { Log.e(TAG, "no mcp url in session"); return@withLock null }
                assistivePrompt = sessObj.optJSONObject("experimental")?.optString("assistive_prompt").orEmpty()

                // MCP handshake: initialize → initialized notification.
                rpc(url, key, "initialize", JSONObject()
                    .put("protocolVersion", PROTOCOL)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "omnipin").put("version", "1.0")))
                    ?: run { Log.e(TAG, "mcp initialize failed"); return@withLock null }
                notify(url, key, "notifications/initialized")

                mcpUrl = url; initialized = true
                Log.d(TAG, "Tool Router session ready: $url")
                url
            } catch (e: Exception) {
                Log.e(TAG, "ensureSession failed", e); null
            }
        }
    }

    /** The meta-tools as an OpenAI tools[] array (for the model). Empty if not configured. */
    suspend fun metaToolSchemas(): JSONArray = withContext(Dispatchers.IO) {
        val key = settings.loadComposioApiKey()
        val url = ensureSession() ?: return@withContext JSONArray()
        try {
            val res = rpc(url, key, "tools/list", JSONObject()) ?: return@withContext JSONArray()
            val tools = res.optJSONObject("result")?.optJSONArray("tools") ?: return@withContext JSONArray()
            val out = JSONArray()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                val name = t.optString("name")
                if (name !in META_TOOLS) continue
                val params = t.optJSONObject("inputSchema")
                    ?: JSONObject().put("type", "object").put("properties", JSONObject())
                out.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", name)
                    .put("description", t.optString("description").take(900))
                    .put("parameters", params)))
            }
            out
        } catch (e: Exception) { Log.e(TAG, "metaToolSchemas failed", e); JSONArray() }
    }

    fun isMetaTool(name: String): Boolean = name in META_TOOLS

    /**
     * Call a meta-tool via MCP. Returns [McpToolResult] with the raw text content and, if the
     * result carries an OAuth/connect link (from MANAGE_CONNECTIONS), the [authUrl] to open.
     */
    suspend fun callTool(name: String, arguments: JSONObject): McpToolResult = withContext(Dispatchers.IO) {
        val key = settings.loadComposioApiKey()
        val url = ensureSession() ?: return@withContext McpToolResult("Composio not configured.", null, false)
        try {
            val res = rpc(url, key, "tools/call", JSONObject().put("name", name).put("arguments", arguments))
                ?: return@withContext McpToolResult("Tool call failed (no response).", null, false)
            res.optJSONObject("error")?.let {
                return@withContext McpToolResult("Error: ${it.optString("message")}", null, false)
            }
            val result = res.optJSONObject("result") ?: JSONObject()
            val text = extractText(result)
            val authUrl = findAuthUrl(text)
            McpToolResult(text, authUrl, !result.optBoolean("isError", false))
        } catch (e: Exception) {
            Log.e(TAG, "callTool $name failed", e)
            McpToolResult("Error calling $name: ${e.message}", null, false)
        }
    }

    // ─── MCP transport (streamable-http: JSON-RPC POST, SSE-framed response) ──────────────

    private fun rpc(url: String, key: String, method: String, params: JSONObject): JSONObject? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", rpcId.getAndIncrement())
            .put("method", method).put("params", params)
        val req = Request.Builder().url(url)
            .addHeader("x-api-key", key)
            .addHeader("content-type", "application/json")
            .addHeader("accept", "application/json, text/event-stream")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) { Log.e(TAG, "$method HTTP ${r.code}: ${raw.take(200)}"); return null }
            return parseSse(raw)
        }
    }

    private fun notify(url: String, key: String, method: String) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        val req = Request.Builder().url(url)
            .addHeader("x-api-key", key)
            .addHeader("content-type", "application/json")
            .addHeader("accept", "application/json, text/event-stream")
            .post(body.toString().toRequestBody(json)).build()
        runCatching { http.newCall(req).execute().use { it.body?.string() } }
    }

    /** Pull the JSON-RPC object out of an SSE response (lines of `data: {json}`) or plain JSON. */
    private fun parseSse(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return runCatching { JSONObject(trimmed) }.getOrNull()
        var last: JSONObject? = null
        for (line in trimmed.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("data:")) {
                val payload = l.removePrefix("data:").trim()
                if (payload.startsWith("{")) runCatching { JSONObject(payload) }.getOrNull()?.let { last = it }
            }
        }
        return last
    }

    /** MCP tool result content is a list of {type:"text", text:"..."} — join the text parts. */
    private fun extractText(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return result.toString()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            if (c.optString("type") == "text") sb.append(c.optString("text"))
        }
        return sb.toString().ifBlank { result.toString() }
    }

    /** Find a connect/OAuth URL inside a MANAGE_CONNECTIONS result so the UI can open it. */
    private fun findAuthUrl(text: String): String? {
        // Composio returns a redirect/auth link; match the first https URL that looks like auth.
        val m = Regex("https://[^\\s\"')]+").findAll(text)
            .map { it.value.trimEnd('.', ',', ')') }
            .firstOrNull { it.contains("composio") || it.contains("oauth") || it.contains("auth") || it.contains("connect") }
        return m
    }
}

/** Outcome of an MCP meta-tool call. [authUrl] is set when the model must connect an app. */
data class McpToolResult(val text: String, val authUrl: String?, val success: Boolean)
