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
        private const val TOOLKITS_URL = "$BASE/api/v3/toolkits"
        private const val AUTHCFG_URL = "$BASE/api/v3/auth_configs"
        private const val CONN_URL = "$BASE/api/v3/connected_accounts"
        const val CALLBACK_URL = "omnipin://composio/callback"
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

    // ─── Connect flow (browse apps → OAuth → Connected ✓) ────────────────

    private fun get(url: String, key: String): Pair<Int, String> {
        val req = Request.Builder().url(url).addHeader("x-api-key", key).get().build()
        http.newCall(req).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private fun post(url: String, key: String, body: JSONObject): Pair<Int, String> {
        val req = Request.Builder().url(url).addHeader("x-api-key", key)
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    /** Browse connectable apps (Composio-managed OAuth), with which are already connected for the user. */
    suspend fun listApps(search: String = ""): Result<List<ComposioApp>> = withContext(Dispatchers.IO) {
        val key = settings.loadComposioApiKey()
        if (key.isBlank()) return@withContext Result.failure(Exception("Set a Composio API key first"))
        try {
            val q = if (search.isBlank()) "" else "&search=${java.net.URLEncoder.encode(search, "UTF-8")}"
            val (code, text) = get("$TOOLKITS_URL?managed_by=composio&sort_by=usage&limit=60$q", key)
            if (code == 401) return@withContext Result.failure(
                Exception("Invalid Composio API key. Get a valid key from app.composio.dev → API Keys and paste it in Settings.")
            )
            if (code != 200) return@withContext Result.failure(Exception("Couldn't load apps (HTTP $code)"))
            val items = JSONObject(text).optJSONArray("items") ?: JSONArray()

            // Which toolkits are already ACTIVE for this user
            val userId = settings.loadComposioUserId()
            val connected = mutableSetOf<String>()
            runCatching {
                val (c2, t2) = get("$CONN_URL?user_ids=$userId&statuses=ACTIVE&limit=100", key)
                if (c2 == 200) {
                    val arr = JSONObject(t2).optJSONArray("items") ?: JSONArray()
                    for (i in 0 until arr.length())
                        arr.getJSONObject(i).optJSONObject("toolkit")?.optString("slug")?.let { connected += it }
                }
            }

            val out = ArrayList<ComposioApp>()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val managed = it.optJSONArray("composio_managed_auth_schemes")?.length() ?: 0
                val noAuth = it.optBoolean("no_auth")
                if (managed == 0 && !noAuth) continue // only one-tap-connectable or no-auth apps
                val slug = it.optString("slug")
                out += ComposioApp(
                    slug = slug,
                    name = it.optString("name", slug),
                    logo = it.optJSONObject("meta")?.optString("logo") ?: "",
                    toolsCount = it.optJSONObject("meta")?.optInt("tools_count") ?: 0,
                    connected = slug in connected,
                    noAuth = noAuth,
                )
            }
            Result.success(out)
        } catch (e: Exception) {
            Log.e(TAG, "listApps failed", e)
            Result.failure(e)
        }
    }

    /** Ensure a Composio-managed auth config exists for the toolkit; returns its id. */
    private fun ensureAuthConfig(slug: String, key: String): String? {
        runCatching {
            val (c, t) = get("$AUTHCFG_URL?toolkit_slug=$slug&is_composio_managed=true&limit=1", key)
            if (c == 200) JSONObject(t).optJSONArray("items")?.optJSONObject(0)?.optString("id")
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val body = JSONObject().put("toolkit", JSONObject().put("slug", slug)).put(
            "auth_config", JSONObject().put("type", "use_composio_managed_auth").put("name", slug)
        )
        val (c, t) = post(AUTHCFG_URL, key, body)
        if (c !in 200..201) { Log.e(TAG, "authConfig $slug HTTP $c: ${t.take(160)}"); return null }
        return JSONObject(t).optJSONObject("auth_config")?.optString("id")
    }

    /** Start OAuth for an app; returns (connectedAccountId, redirectUrl) to open in a Custom Tab. */
    suspend fun initiateConnect(slug: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val key = settings.loadComposioApiKey()
        if (key.isBlank()) return@withContext Result.failure(Exception("Set a Composio API key first"))
        try {
            val acId = ensureAuthConfig(slug, key)
                ?: return@withContext Result.failure(Exception("Couldn't set up auth for $slug"))
            val userId = settings.loadComposioUserId()
            val body = JSONObject()
                .put("auth_config", JSONObject().put("id", acId))
                .put("connection", JSONObject().put("user_id", userId).put("callback_url", CALLBACK_URL))
            val (c, t) = post(CONN_URL, key, body)
            if (c !in 200..201) return@withContext Result.failure(Exception("Connect HTTP $c"))
            val o = JSONObject(t)
            val caId = o.optString("id")
            // The OAuth URL appears under several key names depending on API version:
            // connectionData.val.redirectUrl (camelCase) or .redirect_url, plus top-level
            // redirect_url / redirect_uri. optString returns "" (not null) for missing keys,
            // so pick the first non-blank explicitly.
            val valObj = o.optJSONObject("connectionData")?.optJSONObject("val")
            val redirect = listOfNotNull(
                valObj?.optString("redirectUrl"),
                valObj?.optString("redirect_url"),
                o.optString("redirect_url"),
                o.optString("redirect_uri"),
            ).firstOrNull { it.isNotBlank() }
            if (redirect.isNullOrBlank()) {
                Log.e(TAG, "no redirect in response: ${t.take(300)}")
                return@withContext Result.failure(Exception("No OAuth URL returned"))
            }
            // a fresh listApps()/schemas() will pick up the new tools after connection
            cachedSchemas = null
            Result.success(caId to redirect)
        } catch (e: Exception) {
            Log.e(TAG, "initiateConnect failed", e)
            Result.failure(e)
        }
    }

    /** True once the given connected-account id reaches ACTIVE. */
    suspend fun isConnected(caId: String): Boolean = withContext(Dispatchers.IO) {
        val key = settings.loadComposioApiKey()
        if (key.isBlank()) return@withContext false
        runCatching {
            val (c, t) = get("$CONN_URL/$caId", key)
            if (c == 200) JSONObject(t).optString("status") == "ACTIVE" else false
        }.getOrDefault(false)
    }
}

/** One connectable app for the connect grid. */
data class ComposioApp(
    val slug: String,
    val name: String,
    val logo: String,
    val toolsCount: Int,
    val connected: Boolean,
    val noAuth: Boolean = false, // works without connecting (e.g. the "composio" toolkit)
)
