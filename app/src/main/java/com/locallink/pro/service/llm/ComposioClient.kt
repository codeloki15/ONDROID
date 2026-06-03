package com.locallink.pro.service.llm

import android.util.Log
import com.locallink.pro.data.local.SettingsPreferences
import kotlinx.coroutines.Dispatchers
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

/**
 * Composio (cloud SaaS tools) — opt-in beta. BYO project API key.
 *
 * Fetches a SCOPED set of tool schemas (only the slugs the user configured, to bound the
 * prompt) as OpenAI function definitions, and executes a chosen tool server-side. Tools run
 * on Composio's cloud (Gmail/Slack/GitHub/…), keyed by the user's `user_id`.
 *
 * Composio tool slugs are UPPERCASE_WITH_UNDERSCORES, which never collide with our local
 * snake_case tools — that's how [ToolRouter] dispatches.
 */
@Singleton
class ComposioClient @Inject constructor(
    private val settings: SettingsPreferences,
) {
    companion object {
        private const val TAG = "ComposioClient"
        private const val BASE = "https://backend.composio.dev"
        private const val LIST_URL = "$BASE/api/v3.1/tools"
        private const val EXEC_URL = "$BASE/api/v3/tools/execute"
    }

    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** In-memory cache of fetched OpenAI tool schemas (one fetch per app run, not per turn). */
    @Volatile private var cachedSchemas: JSONArray? = null
    @Volatile private var cachedForSlugs: String = ""

    suspend fun isEnabled(): Boolean = settings.loadComposioApiKey().isNotBlank()

    /** Whether a given tool name is a Composio tool we know about (UPPERCASE slug we fetched). */
    fun isComposioTool(name: String): Boolean =
        cachedSchemas?.let { arr ->
            (0 until arr.length()).any {
                arr.getJSONObject(it).optJSONObject("function")?.optString("name") == name
            }
        } ?: name.matches(Regex("[A-Z][A-Z0-9_]+"))

    /** OpenAI-style tool schemas for the configured Composio slugs (cached). Empty if disabled. */
    suspend fun toolSchemas(): JSONArray = withContext(Dispatchers.IO) {
        val key = settings.loadComposioApiKey()
        if (key.isBlank()) return@withContext JSONArray()
        val slugs = settings.loadComposioTools().trim()
        if (slugs.isBlank()) return@withContext JSONArray()
        cachedSchemas?.let { if (cachedForSlugs == slugs) return@withContext it }

        try {
            val url = "$LIST_URL?tool_slugs=${slugs.replace(" ", "")}&limit=50"
            val req = Request.Builder().url(url).addHeader("x-api-key", key).get().build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@withContext JSONArray()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "list tools ${resp.code}: ${text.take(200)}")
                    return@withContext JSONArray()
                }
                val items = JSONObject(text).optJSONArray("items")
                    ?: JSONObject(text).optJSONArray("data")
                    ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until items.length()) {
                    val t = items.getJSONObject(i)
                    val name = t.optString("slug").ifBlank { t.optString("name") }
                    if (name.isBlank()) continue
                    // input_parameters / parameters is the JSON Schema for arguments
                    val params = t.optJSONObject("input_parameters")
                        ?: t.optJSONObject("parameters")
                        ?: JSONObject().put("type", "object").put("properties", JSONObject())
                    val fn = JSONObject()
                        .put("name", name)
                        .put("description", t.optString("description").take(300))
                        .put("parameters", params)
                    out.put(JSONObject().put("type", "function").put("function", fn))
                }
                cachedSchemas = out
                cachedForSlugs = slugs
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "toolSchemas failed", e)
            JSONArray()
        }
    }

    /** Execute a Composio tool server-side; returns a result string for the LLM. */
    suspend fun execute(slug: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val key = settings.loadComposioApiKey()
        if (key.isBlank()) return@withContext JSONObject().put("error", "Composio key not set").toString()
        val userId = settings.loadComposioUserId()
        try {
            val body = JSONObject().put("user_id", userId).put("arguments", arguments)
            val req = Request.Builder()
                .url("$EXEC_URL/$slug")
                .addHeader("x-api-key", key)
                .post(body.toString().toRequestBody(json))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    Log.e(TAG, "execute $slug ${resp.code}: ${text.take(200)}")
                    return@withContext JSONObject().put("error", "Composio $slug failed (${resp.code})").toString()
                }
                // Composio wraps results; surface the useful bit but keep it a string.
                val o = JSONObject(text)
                (o.opt("data") ?: o.opt("response_data") ?: o).toString().take(2000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            JSONObject().put("error", "Composio error: ${e.message}").toString()
        }
    }
}
